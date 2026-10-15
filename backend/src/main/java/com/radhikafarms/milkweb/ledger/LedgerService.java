package com.radhikafarms.milkweb.ledger;

import com.radhikafarms.milkweb.billing.Bill;
import com.radhikafarms.milkweb.billing.BillRepository;
import com.radhikafarms.milkweb.billing.BillStatus;
import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.customer.CustomerRepository;
import com.radhikafarms.milkweb.customer.exception.CustomerNotFoundException;
import com.radhikafarms.milkweb.ledger.LedgerResponses.AccountResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.AccountTotals;
import com.radhikafarms.milkweb.ledger.LedgerResponses.AdjustmentEntry;
import com.radhikafarms.milkweb.ledger.LedgerResponses.AdjustmentResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.AppliedAmount;
import com.radhikafarms.milkweb.ledger.LedgerResponses.BillBalanceResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.BillLedgerEntry;
import com.radhikafarms.milkweb.ledger.LedgerResponses.BillPaymentStatus;
import com.radhikafarms.milkweb.ledger.LedgerResponses.CustomerAccountSummary;
import com.radhikafarms.milkweb.ledger.LedgerResponses.PaymentResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.RefundResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.ReversalResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.TransactionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The customer money ledger. Rules (owner decisions of 2026-10-15, docs/financial-policy-decisions.md):
 * <ul>
 *   <li>A payment clears the oldest unpaid issued bill first; anything left becomes credit.</li>
 *   <li>Credit is applied automatically, oldest bill first, whenever a bill is issued or a charge is added.</li>
 *   <li>Money returned can never exceed the credit held.</li>
 *   <li>Nothing is edited or deleted. A wrong payment is reversed with a reason; adjustments carry a reason.</li>
 * </ul>
 * <p>Balances are always calculated from the event rows:
 * <pre>
 *   bill due        = bill total + charges on it - credits on it - net credit applied to it   (never negative)
 *   customer credit = active payments + account credits - money returned - net credit applied (never negative)
 * </pre>
 * <p>Every write locks the customer row first ({@link CustomerRepository#findByIdForUpdate}), so two concurrent
 * entries for one customer cannot spend the same credit twice. Reads of bills inside a write take no row locks,
 * which keeps the lock order (bill, then customer) used by bill issuing free of deadlocks.
 */
@Service
@Transactional(readOnly = true)
public class LedgerService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final String EXCESS_KEY_SUFFIX = "/excess";

    private final PaymentRepository payments;
    private final PaymentReversalRepository reversals;
    private final AccountAdjustmentRepository adjustments;
    private final RefundRepository refunds;
    private final BillAllocationRepository allocations;
    private final BillRepository bills;
    private final CustomerRepository customers;
    private final Clock businessClock;

    public LedgerService(PaymentRepository payments, PaymentReversalRepository reversals,
                         AccountAdjustmentRepository adjustments, RefundRepository refunds,
                         BillAllocationRepository allocations, BillRepository bills, CustomerRepository customers,
                         Clock businessClock) {
        this.payments = payments;
        this.reversals = reversals;
        this.adjustments = adjustments;
        this.refunds = refunds;
        this.allocations = allocations;
        this.bills = bills;
        this.customers = customers;
        this.businessClock = businessClock;
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public PaymentResponse recordPayment(UUID customerId, RecordPaymentRequest request, String idempotencyKey,
                                         UUID ownerUserId) {
        requireKey(idempotencyKey);
        lockCustomer(customerId);
        LocalDate receivedOn = notInFuture(request.receivedOn(), "Payment date");
        String reference = blankToNull(request.reference());
        String note = blankToNull(request.note());

        Optional<Payment> existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            boolean same = payment.getCustomerId().equals(customerId)
                    && payment.getAmount().compareTo(request.amount()) == 0
                    && payment.getMethod() == request.method()
                    && Objects.equals(payment.getReference(), reference)
                    && Objects.equals(payment.getNote(), note)
                    && payment.getReceivedOn().equals(receivedOn);
            if (!same) throw new LedgerConflictException("This request key was already used for a different payment.");
            return paymentResponse(payment);
        }

        Payment payment = payments.saveAndFlush(new Payment(customerId, money(request.amount()), request.method(),
                reference, note, receivedOn, now(), ownerUserId, idempotencyKey));
        applyCredit(customerId, AllocationTrigger.PAYMENT, payment.getId());
        return paymentResponse(payment);
    }

    @Transactional
    public ReversalResponse reversePayment(UUID paymentId, ReversePaymentRequest request, UUID ownerUserId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new LedgerNotFoundException("Payment not found: " + paymentId));
        lockCustomer(payment.getCustomerId());

        Optional<PaymentReversal> existing = reversals.findByPaymentId(paymentId);
        if (existing.isPresent()) return reversalResponse(existing.get());

        PaymentReversal reversal = reversals.saveAndFlush(
                new PaymentReversal(paymentId, request.reason().trim(), now(), ownerUserId));

        // Without this payment the credit may now be negative: take back what it paid, newest bill first.
        BigDecimal deficit = availableCredit(payment.getCustomerId()).negate();
        if (deficit.signum() > 0) {
            List<BillBalance> issued = billBalances(payment.getCustomerId());
            for (int index = issued.size() - 1; index >= 0 && deficit.signum() > 0; index--) {
                BillBalance balance = issued.get(index);
                BigDecimal take = balance.paid().min(deficit);
                if (take.signum() <= 0) continue;
                allocations.save(new BillAllocation(payment.getCustomerId(), balance.bill().getId(),
                        AllocationKind.RELEASE, take, AllocationTrigger.PAYMENT_REVERSAL, reversal.getId(), now()));
                deficit = deficit.subtract(take);
            }
            if (deficit.signum() > 0) {
                // The money from this payment was already given back to the customer. Rolling back keeps the
                // ledger from showing negative credit; the owner must sort the cash out first.
                throw new LedgerConflictException("This payment cannot be reversed because its money was already "
                        + "returned to the customer. Record the correction another way.");
            }
            allocations.flush();
        }
        return reversalResponse(reversal);
    }

    @Transactional
    public AdjustmentResponse recordAdjustment(UUID customerId, RecordAdjustmentRequest request, String idempotencyKey,
                                               UUID ownerUserId) {
        requireKey(idempotencyKey);
        lockCustomer(customerId);
        String reason = request.reason().trim();
        BigDecimal amount = money(request.amount());

        Optional<AccountAdjustment> existing = adjustments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            AccountAdjustment first = existing.get();
            Optional<AccountAdjustment> excess = adjustments.findByIdempotencyKey(idempotencyKey + EXCESS_KEY_SUFFIX);
            BigDecimal recorded = first.getAmount().add(excess.map(AccountAdjustment::getAmount).orElse(ZERO));
            boolean same = first.getCustomerId().equals(customerId)
                    && first.getDirection() == request.direction()
                    && recorded.compareTo(amount) == 0
                    && first.getReason().equals(reason)
                    // An account-level first row can come from a bill credit whose whole amount was excess.
                    && (first.getBillId() == null || first.getBillId().equals(request.billId()));
            if (!same) throw new LedgerConflictException("This request key was already used for a different adjustment.");
            List<AccountAdjustment> rows = new ArrayList<>(List.of(first));
            excess.ifPresent(rows::add);
            return adjustmentResponse(customerId, rows, first.getId());
        }

        List<AccountAdjustment> rows = new ArrayList<>();
        if (request.billId() == null) {
            if (request.direction() != AdjustmentDirection.CREDIT) {
                throw new LedgerValidationException("An extra charge must be added to a specific bill.");
            }
            rows.add(new AccountAdjustment(customerId, null, AdjustmentDirection.CREDIT, amount, reason, now(),
                    ownerUserId, idempotencyKey));
        } else {
            BillBalance balance = billBalances(customerId).stream()
                    .filter(candidate -> candidate.bill().getId().equals(request.billId()))
                    .findFirst()
                    .orElseThrow(() -> new LedgerNotFoundException(
                            "No issued bill " + request.billId() + " for this customer."));
            if (request.direction() == AdjustmentDirection.DEBIT) {
                rows.add(new AccountAdjustment(customerId, request.billId(), AdjustmentDirection.DEBIT, amount, reason,
                        now(), ownerUserId, idempotencyKey));
            } else {
                // A bill credit can only lower what is still owed on that bill; any excess becomes account credit.
                BigDecimal onBill = amount.min(balance.due());
                BigDecimal excess = amount.subtract(onBill);
                if (onBill.signum() > 0) {
                    rows.add(new AccountAdjustment(customerId, request.billId(), AdjustmentDirection.CREDIT, onBill,
                            reason, now(), ownerUserId, idempotencyKey));
                }
                if (excess.signum() > 0) {
                    rows.add(new AccountAdjustment(customerId, null, AdjustmentDirection.CREDIT, excess, reason,
                            now(), ownerUserId, onBill.signum() > 0 ? idempotencyKey + EXCESS_KEY_SUFFIX : idempotencyKey));
                }
            }
        }
        List<AccountAdjustment> saved = adjustments.saveAllAndFlush(rows);
        UUID triggerId = saved.getFirst().getId();
        applyCredit(customerId, AllocationTrigger.ADJUSTMENT, triggerId);
        return adjustmentResponse(customerId, saved, triggerId);
    }

    @Transactional
    public RefundResponse recordRefund(UUID customerId, RecordRefundRequest request, String idempotencyKey,
                                       UUID ownerUserId) {
        requireKey(idempotencyKey);
        lockCustomer(customerId);
        LocalDate refundedOn = notInFuture(request.refundedOn(), "Refund date");
        String reason = request.reason().trim();
        BigDecimal amount = money(request.amount());

        Optional<Refund> existing = refunds.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Refund refund = existing.get();
            boolean same = refund.getCustomerId().equals(customerId) && refund.getAmount().compareTo(amount) == 0
                    && refund.getMethod() == request.method() && refund.getReason().equals(reason)
                    && refund.getRefundedOn().equals(refundedOn);
            if (!same) throw new LedgerConflictException("This request key was already used for a different refund.");
            return refundResponse(refund);
        }

        BigDecimal credit = availableCredit(customerId);
        if (amount.compareTo(credit) > 0) {
            throw new LedgerConflictException("Only ₹" + credit.toPlainString() + " of credit is available to return.");
        }
        Refund refund = refunds.saveAndFlush(new Refund(customerId, amount, request.method(), reason, refundedOn,
                now(), ownerUserId, idempotencyKey));
        return refundResponse(refund);
    }

    /** Called by bill issuing, inside its transaction: applies any held credit to the newly issued bill. */
    @Transactional
    public void applyCreditAfterBillIssued(UUID customerId, UUID billId) {
        lockCustomer(customerId);
        applyCredit(customerId, AllocationTrigger.BILL_ISSUE, billId);
    }

    // ------------------------------------------------------------------ reads

    public AccountResponse account(UUID customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found for id: " + customerId));
        List<BillBalance> balances = billBalances(customerId);
        Map<UUID, List<BillLedgerEntry>> entries = billEntries(balances);
        BigDecimal amountDue = balances.stream().map(BillBalance::due).reduce(ZERO, BigDecimal::add);
        BigDecimal totalBilled = balances.stream().map(BillBalance::amountToPay).reduce(ZERO, BigDecimal::add);
        List<BillBalanceResponse> billResponses = balances.reversed().stream()
                .map(balance -> billBalanceResponse(balance, entries.getOrDefault(balance.bill().getId(), List.of())))
                .toList();
        return new AccountResponse(customerId, customer.getName(), Ledger.CURRENCY, amountDue,
                availableCredit(customerId), totalBilled, money(payments.sumActive(customerId)), billResponses);
    }

    public AccountTotals totals(UUID customerId) {
        BigDecimal due = billBalances(customerId).stream().map(BillBalance::due).reduce(ZERO, BigDecimal::add);
        return new AccountTotals(due, availableCredit(customerId));
    }

    /** Every customer who owes money or holds credit, largest amount owed first. */
    public List<CustomerAccountSummary> accountsWithBalances() {
        return customers.findAll().stream()
                .map(customer -> {
                    AccountTotals totals = totals(customer.getId());
                    return new CustomerAccountSummary(customer.getId(), customer.getName(), customer.getPhone(),
                            totals.amountDue(), totals.credit());
                })
                .filter(summary -> summary.amountDue().signum() > 0 || summary.credit().signum() > 0)
                .sorted(Comparator.comparing(CustomerAccountSummary::amountDue).reversed()
                        .thenComparing(CustomerAccountSummary::name))
                .toList();
    }

    /** Payment status of every issued bill in a billing month. */
    public List<BillBalanceResponse> periodBalances(YearMonth month) {
        List<Bill> issued = bills.findByPeriodStartAndStatusOrderByCustomerNameSnapshotAsc(month.atDay(1), BillStatus.ISSUED);
        List<BillBalance> balances = balancesFor(issued);
        Map<UUID, List<BillLedgerEntry>> entries = billEntries(balances);
        return balances.stream()
                .map(balance -> billBalanceResponse(balance, entries.getOrDefault(balance.bill().getId(), List.of())))
                .toList();
    }

    /** The customer's money history, newest first. Owner notes are included only when {@code includeNotes}. */
    public List<TransactionResponse> transactions(UUID customerId, int limit, boolean includeNotes) {
        List<TransactionResponse> history = new ArrayList<>();
        List<Payment> customerPayments = payments.findByCustomerIdOrderByReceivedOnDescRecordedAtDesc(customerId);
        Map<UUID, PaymentReversal> reversed = reversals.findByPaymentIdIn(
                        customerPayments.stream().map(Payment::getId).toList()).stream()
                .collect(Collectors.toMap(PaymentReversal::getPaymentId, Function.identity()));
        for (Payment payment : customerPayments) {
            history.add(new TransactionResponse(payment.getId(), "PAYMENT", payment.getReceivedOn(),
                    payment.getRecordedAt(), payment.getAmount(), payment.getMethod(), payment.getReference(), null,
                    includeNotes ? payment.getNote() : null, null, null, payment.getId(),
                    reversed.containsKey(payment.getId())));
            PaymentReversal reversal = reversed.get(payment.getId());
            if (reversal != null) {
                history.add(new TransactionResponse(reversal.getId(), "PAYMENT_REVERSAL", businessDate(reversal.getRecordedAt()),
                        reversal.getRecordedAt(), payment.getAmount().negate(), payment.getMethod(), payment.getReference(),
                        reversal.getReason(), null, null, null, payment.getId(), false));
            }
        }
        for (Refund refund : refunds.findByCustomerIdOrderByRefundedOnDescRecordedAtDesc(customerId)) {
            history.add(new TransactionResponse(refund.getId(), "REFUND", refund.getRefundedOn(), refund.getRecordedAt(),
                    refund.getAmount(), refund.getMethod(), null, refund.getReason(), null, null, null, null, false));
        }
        Map<UUID, String> periods = billPeriods(customerId);
        for (AccountAdjustment adjustment : adjustments.findByCustomerIdOrderByRecordedAtDesc(customerId)) {
            history.add(new TransactionResponse(adjustment.getId(),
                    adjustment.getDirection() == AdjustmentDirection.CREDIT ? "CREDIT" : "CHARGE",
                    businessDate(adjustment.getRecordedAt()), adjustment.getRecordedAt(), adjustment.getAmount(), null,
                    null, adjustment.getReason(), null, adjustment.getBillId(),
                    adjustment.getBillId() == null ? null : periods.get(adjustment.getBillId()), null, false));
        }
        history.sort(Comparator.comparing(TransactionResponse::date).thenComparing(TransactionResponse::recordedAt).reversed());
        return history.size() > limit ? history.subList(0, limit) : history;
    }

    // ------------------------------------------------------------------ calculations

    /** An issued bill with its calculated balance. */
    record BillBalance(Bill bill, BigDecimal charges, BigDecimal credits, BigDecimal paid) {
        BigDecimal amountToPay() {
            return bill.getTotalAmount().add(charges).subtract(credits);
        }

        BigDecimal due() {
            return amountToPay().subtract(paid).max(ZERO);
        }
    }

    /** Credit the customer holds right now: money and credits not yet applied to a bill or returned. */
    BigDecimal availableCredit(UUID customerId) {
        return money(payments.sumActive(customerId))
                .add(money(adjustments.sumAccountCredits(customerId)))
                .subtract(money(refunds.sumForCustomer(customerId)))
                .subtract(money(allocations.sumNetForCustomer(customerId)));
    }

    List<BillBalance> billBalances(UUID customerId) {
        return balancesFor(bills.findByCustomer_IdAndStatusOrderByPeriodStartAscCreatedAtAsc(customerId, BillStatus.ISSUED));
    }

    private List<BillBalance> balancesFor(List<Bill> issued) {
        if (issued.isEmpty()) return List.of();
        List<UUID> billIds = issued.stream().map(Bill::getId).toList();
        Map<UUID, BigDecimal> charges = new HashMap<>();
        Map<UUID, BigDecimal> credits = new HashMap<>();
        for (UUID customerId : issued.stream().map(bill -> bill.getCustomer().getId()).distinct().toList()) {
            for (AccountAdjustment adjustment : adjustments.findByCustomerIdOrderByRecordedAtDesc(customerId)) {
                if (adjustment.getBillId() == null) continue;
                Map<UUID, BigDecimal> target = adjustment.getDirection() == AdjustmentDirection.DEBIT ? charges : credits;
                target.merge(adjustment.getBillId(), adjustment.getAmount(), BigDecimal::add);
            }
        }
        Map<UUID, BigDecimal> paid = new HashMap<>();
        for (BillAllocation allocation : allocations.findByBillIdInOrderByCreatedAtAsc(billIds)) {
            paid.merge(allocation.getBillId(), allocation.signedAmount(), BigDecimal::add);
        }
        return issued.stream()
                .map(bill -> new BillBalance(bill,
                        money(charges.getOrDefault(bill.getId(), ZERO)),
                        money(credits.getOrDefault(bill.getId(), ZERO)),
                        money(paid.getOrDefault(bill.getId(), ZERO))))
                .toList();
    }

    /** Applies the customer's available credit to unpaid issued bills, oldest billing month first. */
    private List<BillAllocation> applyCredit(UUID customerId, AllocationTrigger trigger, UUID triggerId) {
        BigDecimal credit = availableCredit(customerId);
        List<BillAllocation> created = new ArrayList<>();
        if (credit.signum() <= 0) return created;
        for (BillBalance balance : billBalances(customerId)) {
            if (credit.signum() <= 0) break;
            BigDecimal take = balance.due().min(credit);
            if (take.signum() <= 0) continue;
            created.add(allocations.save(new BillAllocation(customerId, balance.bill().getId(), AllocationKind.ALLOCATE,
                    take, trigger, triggerId, now())));
            credit = credit.subtract(take);
        }
        allocations.flush();
        return created;
    }

    // ------------------------------------------------------------------ response mapping

    private PaymentResponse paymentResponse(Payment payment) {
        List<AppliedAmount> applied = applied(payment.getCustomerId(), payment.getId(), AllocationKind.ALLOCATE);
        BigDecimal appliedTotal = applied.stream().map(AppliedAmount::amount).reduce(ZERO, BigDecimal::add);
        return new PaymentResponse(payment.getId(), payment.getCustomerId(), payment.getAmount(), payment.getCurrency(),
                payment.getMethod(), payment.getReference(), payment.getNote(), payment.getReceivedOn(),
                payment.getRecordedAt(), reversals.findByPaymentId(payment.getId()).isPresent(), applied,
                payment.getAmount().subtract(appliedTotal).max(ZERO), totals(payment.getCustomerId()));
    }

    private ReversalResponse reversalResponse(PaymentReversal reversal) {
        Payment payment = payments.findById(reversal.getPaymentId()).orElseThrow();
        return new ReversalResponse(reversal.getId(), reversal.getPaymentId(), reversal.getReason(),
                reversal.getRecordedAt(), applied(payment.getCustomerId(), reversal.getId(), AllocationKind.RELEASE),
                totals(payment.getCustomerId()));
    }

    private AdjustmentResponse adjustmentResponse(UUID customerId, List<AccountAdjustment> rows, UUID triggerId) {
        Map<UUID, String> periods = billPeriods(customerId);
        List<AdjustmentEntry> entries = rows.stream()
                .map(row -> new AdjustmentEntry(row.getId(), row.getBillId(),
                        row.getBillId() == null ? null : periods.get(row.getBillId()), row.getDirection(),
                        row.getAmount(), row.getReason(), row.getRecordedAt()))
                .toList();
        return new AdjustmentResponse(entries, applied(customerId, triggerId, AllocationKind.ALLOCATE), totals(customerId));
    }

    private RefundResponse refundResponse(Refund refund) {
        return new RefundResponse(refund.getId(), refund.getAmount(), refund.getMethod(), refund.getReason(),
                refund.getRefundedOn(), refund.getRecordedAt(), totals(refund.getCustomerId()));
    }

    private List<AppliedAmount> applied(UUID customerId, UUID triggerId, AllocationKind kind) {
        Map<UUID, String> periods = billPeriods(customerId);
        return allocations.findByTriggerIdOrderByCreatedAtAsc(triggerId).stream()
                .filter(allocation -> allocation.getKind() == kind)
                .map(allocation -> new AppliedAmount(allocation.getBillId(), periods.get(allocation.getBillId()),
                        allocation.getAmount()))
                .toList();
    }

    private BillBalanceResponse billBalanceResponse(BillBalance balance, List<BillLedgerEntry> entries) {
        Bill bill = balance.bill();
        BillPaymentStatus status = balance.due().signum() == 0 ? BillPaymentStatus.PAID
                : balance.paid().signum() > 0 || balance.credits().signum() > 0 ? BillPaymentStatus.PART_PAID
                : BillPaymentStatus.UNPAID;
        return new BillBalanceResponse(bill.getId(), bill.getCustomer().getId(), bill.getCustomerNameSnapshot(),
                YearMonth.from(bill.getPeriodStart()).toString(), bill.getPeriodStart(), bill.getIssuedAt(),
                bill.getTotalAmount(), balance.charges(), balance.credits(), balance.amountToPay(), balance.paid(),
                balance.due(), status, entries);
    }

    /** Payment history lines for each bill, in the order they happened. */
    private Map<UUID, List<BillLedgerEntry>> billEntries(List<BillBalance> balances) {
        if (balances.isEmpty()) return Map.of();
        List<UUID> billIds = balances.stream().map(balance -> balance.bill().getId()).toList();
        record Dated(Instant at, UUID billId, BillLedgerEntry entry) {
        }
        List<Dated> dated = new ArrayList<>();
        List<BillAllocation> billAllocations = allocations.findByBillIdInOrderByCreatedAtAsc(billIds);
        Map<UUID, Payment> paymentsById = payments.findAllById(billAllocations.stream()
                        .filter(allocation -> allocation.getTriggerType() == AllocationTrigger.PAYMENT)
                        .map(BillAllocation::getTriggerId).distinct().toList()).stream()
                .collect(Collectors.toMap(Payment::getId, Function.identity()));
        for (BillAllocation allocation : billAllocations) {
            BillLedgerEntry entry = switch (allocation.getTriggerType()) {
                case PAYMENT -> {
                    Payment payment = paymentsById.get(allocation.getTriggerId());
                    yield new BillLedgerEntry("PAYMENT", payment.getReceivedOn(), payment.getMethod(),
                            allocation.getAmount(), null);
                }
                case PAYMENT_REVERSAL -> new BillLedgerEntry("PAYMENT_REVERSED", businessDate(allocation.getCreatedAt()),
                        null, allocation.getAmount().negate(), null);
                case ADJUSTMENT, BILL_ISSUE -> new BillLedgerEntry("CREDIT_APPLIED", businessDate(allocation.getCreatedAt()),
                        null, allocation.getAmount(), null);
            };
            dated.add(new Dated(allocation.getCreatedAt(), allocation.getBillId(), entry));
        }
        for (UUID customerId : balances.stream().map(balance -> balance.bill().getCustomer().getId()).distinct().toList()) {
            for (AccountAdjustment adjustment : adjustments.findByCustomerIdOrderByRecordedAtDesc(customerId)) {
                if (adjustment.getBillId() == null || !billIds.contains(adjustment.getBillId())) continue;
                boolean credit = adjustment.getDirection() == AdjustmentDirection.CREDIT;
                dated.add(new Dated(adjustment.getRecordedAt(), adjustment.getBillId(), new BillLedgerEntry(
                        credit ? "ADJUSTMENT_CREDIT" : "ADJUSTMENT_CHARGE", businessDate(adjustment.getRecordedAt()), null,
                        credit ? adjustment.getAmount().negate() : adjustment.getAmount(), adjustment.getReason())));
            }
        }
        return dated.stream()
                .sorted(Comparator.comparing(Dated::at))
                .collect(Collectors.groupingBy(Dated::billId, Collectors.mapping(Dated::entry, Collectors.toList())));
    }

    private Map<UUID, String> billPeriods(UUID customerId) {
        return bills.findByCustomer_IdAndStatusOrderByPeriodStartAscCreatedAtAsc(customerId, BillStatus.ISSUED).stream()
                .collect(Collectors.toMap(Bill::getId, bill -> YearMonth.from(bill.getPeriodStart()).toString()));
    }

    // ------------------------------------------------------------------ helpers

    private void lockCustomer(UUID customerId) {
        customers.findByIdForUpdate(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found for id: " + customerId));
    }

    private void requireKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new LedgerValidationException("Idempotency-Key header must be 8-64 letters, digits, '-' or '_'.");
        }
    }

    private LocalDate notInFuture(LocalDate date, String label) {
        LocalDate today = LocalDate.now(businessClock);
        if (date == null) return today;
        if (date.isAfter(today)) throw new LedgerValidationException(label + " cannot be in the future.");
        return date;
    }

    private Instant now() {
        return Instant.now(businessClock);
    }

    private LocalDate businessDate(Instant instant) {
        return LocalDate.ofInstant(instant, businessClock.getZone());
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
