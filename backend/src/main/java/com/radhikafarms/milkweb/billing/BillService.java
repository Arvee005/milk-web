package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.billing.exception.BillConflictException;
import com.radhikafarms.milkweb.billing.exception.BillNotFoundException;
import com.radhikafarms.milkweb.customer.BillingType;
import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.customer.CustomerRepository;
import com.radhikafarms.milkweb.customer.exception.CustomerNotFoundException;
import com.radhikafarms.milkweb.dailyrecord.DailyRecord;
import com.radhikafarms.milkweb.dailyrecord.DailyRecordRepository;
import com.radhikafarms.milkweb.ledger.LedgerService;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class BillService {

    private final BillRepository billRepository;
    private final BillingPeriodRepository billingPeriodRepository;
    private final CustomerRepository customerRepository;
    private final DailyRecordRepository dailyRecordRepository;
    private final UserRepository userRepository;
    private final Clock businessClock;
    private final LedgerService ledgerService;

    public BillService(
            BillRepository billRepository,
            BillingPeriodRepository billingPeriodRepository,
            CustomerRepository customerRepository,
            DailyRecordRepository dailyRecordRepository,
            UserRepository userRepository,
            Clock businessClock,
            LedgerService ledgerService
    ) {
        this.billRepository = billRepository;
        this.billingPeriodRepository = billingPeriodRepository;
        this.customerRepository = customerRepository;
        this.dailyRecordRepository = dailyRecordRepository;
        this.userRepository = userRepository;
        this.businessClock = businessClock;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public BillResponse generateDraft(String periodIdentifier, UUID customerId) {
        YearMonth month = parsePeriod(periodIdentifier);
        BillingPeriod period = billingPeriodRepository.findByPeriodStartForUpdate(month.atDay(1))
                .orElseThrow(() -> new BillConflictException("Billing period must be closed before bill generation"));
        if (period.getStatus() != BillingPeriodStatus.CLOSED) {
            throw new BillConflictException("Billing period must be closed before bill generation");
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found for id: " + customerId));
        var existing = billRepository.findByBillingPeriod_IdAndCustomer_Id(period.getId(), customerId);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        if (customer.getBillingType() != BillingType.MONTHLY) {
            throw new BillConflictException("Only MONTHLY billing customers are eligible for bills");
        }

        List<DailyRecord> records = dailyRecordRepository.findChargeableRecords(
                customerId, period.getPeriodStart(), period.getPeriodEnd());
        BigDecimal total = records.stream()
                .map(DailyRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            throw new BillConflictException("No non-zero chargeable records are available for this customer and period");
        }

        Bill bill = new Bill(
                period,
                customer,
                customer.getName(),
                customer.getPhone(),
                customer.getAddress(),
                total);
        records.forEach(record -> bill.addLine(new BillLine(
                record,
                record.getProduct().getName() + " (" + record.getProduct().getUnit().name() + ")")));
        return toResponse(billRepository.saveAndFlush(bill));
    }

    public BillResponse getBill(UUID billId) {
        Bill bill = billRepository.findByIdWithLines(billId)
                .orElseThrow(() -> new BillNotFoundException(billId));
        return toResponse(bill);
    }

    public Page<BillResponse> listBills(UUID customerId, String periodIdentifier, Pageable pageable) {
        LocalDate periodStart = periodIdentifier == null ? null : parsePeriod(periodIdentifier).atDay(1);
        return billRepository.findFiltered(customerId, periodStart, pageable).map(this::toResponse);
    }

    @Transactional
    public BillResponse issue(UUID billId, UUID ownerUserId) {
        Bill bill = billRepository.findByIdForUpdate(billId)
                .orElseThrow(() -> new BillNotFoundException(billId));
        if (bill.getStatus() == BillStatus.DRAFT) {
            User owner = userRepository.getReferenceById(ownerUserId);
            bill.issue(owner, Instant.now(businessClock));
            billRepository.flush();
            // Credit the customer already holds (an advance or an overpayment) is applied to the new bill at once.
            ledgerService.applyCreditAfterBillIssued(bill.getCustomer().getId(), bill.getId());
        }
        return toResponse(billRepository.findByIdWithLines(billId).orElseThrow());
    }

    private BillResponse toResponse(Bill bill) {
        String period = YearMonth.from(bill.getPeriodStart()).toString();
        return new BillResponse(
                bill.getId(),
                bill.getCustomer().getId(),
                bill.getCustomerNameSnapshot(),
                bill.getCustomerPhoneSnapshot(),
                bill.getCustomerAddressSnapshot(),
                period,
                bill.getPeriodStart(),
                bill.getPeriodEnd(),
                bill.getTotalAmount(),
                bill.getStatus(),
                bill.getCreatedAt(),
                bill.getIssuedAt(),
                bill.getIssuedBy() == null ? null : bill.getIssuedBy().getId(),
                bill.getLines().stream().map(line -> new BillLineResponse(
                        line.getId(),
                        line.getDailyRecordId(),
                        line.getRecordDate(),
                        line.getSlot(),
                        line.getDescription(),
                        line.getQuantity(),
                        line.getUnitPrice(),
                        line.getAmount())).toList());
    }

    private YearMonth parsePeriod(String periodIdentifier) {
        if (periodIdentifier == null || !periodIdentifier.matches("[0-9]{4}-(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("Period must use YYYY-MM format");
        }
        try {
            YearMonth month = YearMonth.parse(periodIdentifier);
            if (month.getYear() < 1) {
                throw new IllegalArgumentException("Period year must be greater than zero");
            }
            return month;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Period must be a valid calendar month", ex);
        }
    }
}
