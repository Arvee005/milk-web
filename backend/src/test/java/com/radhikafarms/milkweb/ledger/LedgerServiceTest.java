package com.radhikafarms.milkweb.ledger;

import com.radhikafarms.milkweb.billing.BillResponse;
import com.radhikafarms.milkweb.billing.BillService;
import com.radhikafarms.milkweb.billing.BillingPeriodService;
import com.radhikafarms.milkweb.customer.BillingType;
import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.customer.CustomerService;
import com.radhikafarms.milkweb.customer.CustomerStatus;
import com.radhikafarms.milkweb.customer.OwnerCreateCustomerRequest;
import com.radhikafarms.milkweb.dailyrecord.DailyRecordService;
import com.radhikafarms.milkweb.dailyrecord.DailyRecordSlot;
import com.radhikafarms.milkweb.dailyrecord.DailyRecordStatus;
import com.radhikafarms.milkweb.dailyrecord.OwnerCreateDailyRecordRequest;
import com.radhikafarms.milkweb.ledger.LedgerResponses.AccountResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.AppliedAmount;
import com.radhikafarms.milkweb.ledger.LedgerResponses.BillBalanceResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.BillPaymentStatus;
import com.radhikafarms.milkweb.ledger.LedgerResponses.PaymentResponse;
import com.radhikafarms.milkweb.product.OwnerCreateProductRequest;
import com.radhikafarms.milkweb.product.Product;
import com.radhikafarms.milkweb.product.ProductService;
import com.radhikafarms.milkweb.product.ProductUnit;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class LedgerServiceTest {

    @Autowired private LedgerService ledger;
    @Autowired private BillService billService;
    @Autowired private BillingPeriodService billingPeriodService;
    @Autowired private CustomerService customerService;
    @Autowired private ProductService productService;
    @Autowired private DailyRecordService dailyRecordService;
    @Autowired private UserService userService;
    @Autowired private PaymentRepository paymentRepository;

    private UUID ownerId;
    private Customer customer;

    @BeforeEach
    void setUp() {
        ownerId = userService.createUser(phone(), "secret123", UserRole.OWNER).getId();
        customer = customer("Ledger Customer");
    }

    @Test
    void paymentClearsTheOldestBillFirstAndKeepsTheRestAsCredit() {
        BillResponse august = bill(customer, YearMonth.of(2005, 8), "100.00", true);
        BillResponse september = bill(customer, YearMonth.of(2005, 9), "200.00", true);

        PaymentResponse payment = pay(customer, "350.00");

        assertThat(payment.appliedTo()).extracting(AppliedAmount::billId, AppliedAmount::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(august.id(), money("100.00")),
                        org.assertj.core.groups.Tuple.tuple(september.id(), money("200.00")));
        assertThat(payment.keptAsCredit()).isEqualByComparingTo("50.00");
        AccountResponse account = ledger.account(customer.getId());
        assertThat(account.amountDue()).isEqualByComparingTo("0.00");
        assertThat(account.credit()).isEqualByComparingTo("50.00");
        assertThat(account.bills()).extracting(BillBalanceResponse::status)
                .containsOnly(BillPaymentStatus.PAID);
    }

    @Test
    void partialPaymentLeavesTheNewerBillPartPaid() {
        bill(customer, YearMonth.of(2005, 8), "100.00", true);
        BillResponse september = bill(customer, YearMonth.of(2005, 9), "200.00", true);

        pay(customer, "150.00");

        AccountResponse account = ledger.account(customer.getId());
        assertThat(account.amountDue()).isEqualByComparingTo("150.00");
        assertThat(account.credit()).isEqualByComparingTo("0.00");
        BillBalanceResponse sept = balance(account, september.id());
        assertThat(sept.status()).isEqualTo(BillPaymentStatus.PART_PAID);
        assertThat(sept.paid()).isEqualByComparingTo("50.00");
        assertThat(sept.due()).isEqualByComparingTo("150.00");
        // Newest bill first in the account view.
        assertThat(account.bills().getFirst().billId()).isEqualTo(september.id());
    }

    @Test
    void advanceCreditIsAppliedAutomaticallyWhenTheNextBillIsIssued() {
        PaymentResponse advance = pay(customer, "150.00");
        assertThat(advance.appliedTo()).isEmpty();
        assertThat(advance.keptAsCredit()).isEqualByComparingTo("150.00");

        BillResponse draft = bill(customer, YearMonth.of(2005, 10), "100.00", false);
        // A draft is not owed yet, so nothing is applied to it.
        assertThat(ledger.totals(customer.getId()).credit()).isEqualByComparingTo("150.00");

        billService.issue(draft.id(), ownerId);

        AccountResponse account = ledger.account(customer.getId());
        assertThat(balance(account, draft.id()).status()).isEqualTo(BillPaymentStatus.PAID);
        assertThat(account.credit()).isEqualByComparingTo("50.00");
        assertThat(balance(account, draft.id()).entries()).extracting(LedgerResponses.BillLedgerEntry::type)
                .containsExactly("CREDIT_APPLIED");
    }

    @Test
    void refundCannotExceedTheCreditHeld() {
        pay(customer, "50.00");

        ledger.recordRefund(customer.getId(), new RecordRefundRequest(money("30.00"), PaymentMethod.CASH,
                "Returned extra cash", null), key(), ownerId);
        assertThat(ledger.totals(customer.getId()).credit()).isEqualByComparingTo("20.00");

        assertThatThrownBy(() -> ledger.recordRefund(customer.getId(), new RecordRefundRequest(money("20.01"),
                PaymentMethod.CASH, "Too much", null), key(), ownerId))
                .isInstanceOf(LedgerConflictException.class)
                .hasMessageContaining("₹20.00");
    }

    @Test
    void reversingAPaymentTakesItsMoneyBackFromTheNewestBillsFirst() {
        BillResponse august = bill(customer, YearMonth.of(2005, 8), "100.00", true);
        BillResponse september = bill(customer, YearMonth.of(2005, 9), "200.00", true);
        pay(customer, "100.00");
        PaymentResponse wrong = pay(customer, "150.00");

        var reversal = ledger.reversePayment(wrong.id(), new ReversePaymentRequest("Typed 150 instead of 15"), ownerId);

        assertThat(reversal.takenBack()).extracting(AppliedAmount::billId).containsExactly(september.id());
        AccountResponse account = ledger.account(customer.getId());
        assertThat(balance(account, august.id()).status()).isEqualTo(BillPaymentStatus.PAID);
        assertThat(balance(account, september.id()).status()).isEqualTo(BillPaymentStatus.UNPAID);
        assertThat(account.amountDue()).isEqualByComparingTo("200.00");
        assertThat(account.totalReceived()).isEqualByComparingTo("100.00");
        // The original payment row is untouched; history shows both entries.
        assertThat(paymentRepository.findById(wrong.id())).isPresent();
        assertThat(ledger.transactions(customer.getId(), 100, true))
                .extracting(LedgerResponses.TransactionResponse::type)
                .contains("PAYMENT", "PAYMENT_REVERSAL");

        // Reversing again returns the same reversal instead of taking money back twice.
        var again = ledger.reversePayment(wrong.id(), new ReversePaymentRequest("Typed 150 instead of 15"), ownerId);
        assertThat(again.id()).isEqualTo(reversal.id());
        assertThat(ledger.totals(customer.getId()).amountDue()).isEqualByComparingTo("200.00");
    }

    @Test
    void reversingAPaymentThatOnlyHeldCreditJustRemovesTheCredit() {
        PaymentResponse advance = pay(customer, "80.00");
        ledger.reversePayment(advance.id(), new ReversePaymentRequest("Wrong customer"), ownerId);
        assertThat(ledger.totals(customer.getId()).credit()).isEqualByComparingTo("0.00");
        assertThat(ledger.totals(customer.getId()).amountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void billCreditLowersWhatIsOwedAndAnyExcessBecomesAccountCredit() {
        BillResponse august = bill(customer, YearMonth.of(2005, 8), "100.00", true);
        BillResponse september = bill(customer, YearMonth.of(2005, 9), "200.00", true);

        var sour = ledger.recordAdjustment(customer.getId(), new RecordAdjustmentRequest(AdjustmentDirection.CREDIT,
                money("30.00"), "₹30 off, milk was sour", august.id()), key(), ownerId);
        assertThat(sour.adjustments()).hasSize(1);
        assertThat(balance(ledger.account(customer.getId()), august.id()).due()).isEqualByComparingTo("70.00");

        // 100 of credit on a bill that still needs 70: 70 lowers August, 30 is held and applied to September.
        var large = ledger.recordAdjustment(customer.getId(), new RecordAdjustmentRequest(AdjustmentDirection.CREDIT,
                money("100.00"), "Goodwill", august.id()), key(), ownerId);
        assertThat(large.adjustments()).extracting(LedgerResponses.AdjustmentEntry::billId)
                .containsExactly(august.id(), null);
        AccountResponse account = ledger.account(customer.getId());
        assertThat(balance(account, august.id()).due()).isEqualByComparingTo("0.00");
        assertThat(balance(account, september.id()).due()).isEqualByComparingTo("170.00");
        assertThat(account.credit()).isEqualByComparingTo("0.00");
    }

    @Test
    void extraChargeRaisesTheBillAndUsesHeldCredit() {
        BillResponse august = bill(customer, YearMonth.of(2005, 8), "100.00", true);
        pay(customer, "120.00");
        assertThat(ledger.totals(customer.getId()).credit()).isEqualByComparingTo("20.00");

        ledger.recordAdjustment(customer.getId(), new RecordAdjustmentRequest(AdjustmentDirection.DEBIT,
                money("50.00"), "Extra 1 L on 12 Aug", august.id()), key(), ownerId);

        BillBalanceResponse aug = balance(ledger.account(customer.getId()), august.id());
        assertThat(aug.amountToPay()).isEqualByComparingTo("150.00");
        assertThat(aug.due()).isEqualByComparingTo("30.00");
        assertThat(ledger.totals(customer.getId()).credit()).isEqualByComparingTo("0.00");
    }

    @Test
    void accountLevelChargesAndOtherCustomersBillsAreRejected() {
        assertThatThrownBy(() -> ledger.recordAdjustment(customer.getId(), new RecordAdjustmentRequest(
                AdjustmentDirection.DEBIT, money("10.00"), "No bill", null), key(), ownerId))
                .isInstanceOf(LedgerValidationException.class);

        Customer other = customer("Someone Else");
        BillResponse othersBill = bill(other, YearMonth.of(2005, 8), "100.00", true);
        assertThatThrownBy(() -> ledger.recordAdjustment(customer.getId(), new RecordAdjustmentRequest(
                AdjustmentDirection.CREDIT, money("10.00"), "Wrong bill", othersBill.id()), key(), ownerId))
                .isInstanceOf(LedgerNotFoundException.class);
    }

    @Test
    void repeatingARequestKeyReturnsTheOriginalAndADifferentPayloadIsRefused() {
        String key = key();
        PaymentResponse first = ledger.recordPayment(customer.getId(), new RecordPaymentRequest(money("40.00"),
                PaymentMethod.UPI, "UTR123", null, null), key, ownerId);
        PaymentResponse repeat = ledger.recordPayment(customer.getId(), new RecordPaymentRequest(money("40.00"),
                PaymentMethod.UPI, "UTR123", null, null), key, ownerId);

        assertThat(repeat.id()).isEqualTo(first.id());
        assertThat(ledger.totals(customer.getId()).credit()).isEqualByComparingTo("40.00");
        assertThatThrownBy(() -> ledger.recordPayment(customer.getId(), new RecordPaymentRequest(money("45.00"),
                PaymentMethod.UPI, "UTR123", null, null), key, ownerId))
                .isInstanceOf(LedgerConflictException.class);
    }

    @Test
    void rejectsFutureDatesAndBadKeys() {
        assertThatThrownBy(() -> ledger.recordPayment(customer.getId(), new RecordPaymentRequest(money("10.00"),
                PaymentMethod.CASH, null, null, LocalDate.now().plusDays(3)), key(), ownerId))
                .isInstanceOf(LedgerValidationException.class);
        assertThatThrownBy(() -> ledger.recordPayment(customer.getId(), new RecordPaymentRequest(money("10.00"),
                PaymentMethod.CASH, null, null, null), "short", ownerId))
                .isInstanceOf(LedgerValidationException.class);
    }

    @Test
    void periodBalancesListOnlyIssuedBillsWithTheirStatus() {
        Customer other = customer("Second Customer");
        Customer third = customer("Draft Customer");
        YearMonth august = YearMonth.of(2005, 8);
        record(customer, august, "100.00");
        record(other, august, "60.00");
        record(third, august, "70.00");
        billingPeriodService.closePeriod(august.toString(), ownerId);
        billService.issue(billService.generateDraft(august.toString(), customer.getId()).id(), ownerId);
        billService.issue(billService.generateDraft(august.toString(), other.getId()).id(), ownerId);
        billService.generateDraft(august.toString(), third.getId());
        pay(customer, "100.00");
        pay(other, "20.00");

        List<BillBalanceResponse> balances = ledger.periodBalances(YearMonth.of(2005, 8));

        assertThat(balances).extracting(BillBalanceResponse::customerName, BillBalanceResponse::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Ledger Customer", BillPaymentStatus.PAID),
                        org.assertj.core.groups.Tuple.tuple("Second Customer", BillPaymentStatus.PART_PAID));
        assertThat(ledger.accountsWithBalances())
                .extracting(LedgerResponses.CustomerAccountSummary::name)
                .contains("Second Customer")
                .doesNotContain("Ledger Customer");
    }

    @Test
    void customerHistoryHidesOwnerNotes() {
        ledger.recordPayment(customer.getId(), new RecordPaymentRequest(money("10.00"), PaymentMethod.CASH, null,
                "Paid by neighbour", null), key(), ownerId);

        assertThat(ledger.transactions(customer.getId(), 100, true).getFirst().note()).isEqualTo("Paid by neighbour");
        assertThat(ledger.transactions(customer.getId(), 100, false).getFirst().note()).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private PaymentResponse pay(Customer target, String amount) {
        return ledger.recordPayment(target.getId(), new RecordPaymentRequest(money(amount), PaymentMethod.CASH, null,
                null, null), key(), ownerId);
    }

    /** One collection worth {@code amount}, then the month is closed and the customer's bill generated. */
    private BillResponse bill(Customer target, YearMonth month, String amount, boolean issue) {
        record(target, month, amount);
        billingPeriodService.closePeriod(month.toString(), ownerId);
        BillResponse draft = billService.generateDraft(month.toString(), target.getId());
        return issue ? billService.issue(draft.id(), ownerId) : draft;
    }

    private void record(Customer target, YearMonth month, String amount) {
        Product product = productService.createProduct(new OwnerCreateProductRequest(
                "Ledger Product " + UUID.randomUUID(), null, ProductUnit.LITRE, money(amount)));
        dailyRecordService.createDailyRecord(new OwnerCreateDailyRecordRequest(target.getId(), product.getId(), null,
                month.atDay(2), DailyRecordSlot.MORNING, BigDecimal.ONE, money(amount), DailyRecordStatus.COLLECTED));
    }

    private BillBalanceResponse balance(AccountResponse account, UUID billId) {
        return account.bills().stream().filter(b -> b.billId().equals(billId)).findFirst().orElseThrow();
    }

    private Customer customer(String name) {
        return customerService.createCustomer(new OwnerCreateCustomerRequest(phone(), "secret123", name, null,
                BillingType.MONTHLY, CustomerStatus.ACTIVE, LocalDate.of(2005, 1, 1)));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String phone() {
        return "96" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }
}
