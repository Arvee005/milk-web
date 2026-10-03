package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.billing.exception.BillConflictException;
import com.radhikafarms.milkweb.billing.exception.PeriodClosedException;
import com.radhikafarms.milkweb.customer.*;
import com.radhikafarms.milkweb.dailyrecord.*;
import com.radhikafarms.milkweb.product.*;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BillServiceTest {

    @Autowired
    private BillService billService;

    @Autowired
    private BillingPeriodService billingPeriodService;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private DailyRecordService dailyRecordService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ProductService productService;

    @Autowired
    private UserService userService;

    @Autowired
    private Clock businessClock;

    @Test
    void generatesDraftFromChargeableRecordsAndPreservesDailyRecordSnapshots() {
        User owner = owner();
        Customer customer = customer(BillingType.MONTHLY);
        Product product = product();
        YearMonth month = YearMonth.of(2003, 1);
        LocalDate date = month.atDay(3);

        DailyRecord collected = createRecord(customer, product, date, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "2", "60.00");
        DailyRecord extra = createRecord(customer, product, date, DailyRecordStatus.EXTRA,
                DailyRecordSlot.EVENING, "1.250", "40.00");
        createRecord(customer, product, date.plusDays(1), DailyRecordStatus.SKIPPED,
                DailyRecordSlot.MORNING, "0", null);
        createRecord(customer, product, date.plusDays(1), DailyRecordStatus.PAUSED,
                DailyRecordSlot.EVENING, "0", null);
        product.setPrice(new BigDecimal("99.00"));
        productService.updateProduct(product.getId(), new OwnerUpdateProductRequest(
                product.getName(), product.getDescription(), product.getUnit(), new BigDecimal("99.00"),
                product.getStatus()));
        billingPeriodService.closePeriod(month.toString(), owner.getId());

        BillResponse bill = billService.generateDraft(month.toString(), customer.getId());

        assertThat(bill.status()).isEqualTo(BillStatus.DRAFT);
        assertThat(bill.totalAmount()).isEqualByComparingTo("170.00");
        assertThat(bill.customerName()).isEqualTo(customer.getName());
        assertThat(bill.lines()).hasSize(2);
        assertThat(bill.lines()).extracting(BillLineResponse::dailyRecordId)
                .containsExactlyInAnyOrder(collected.getId(), extra.getId());
        BillLineResponse morning = bill.lines().stream()
                .filter(line -> line.slot().equals(DailyRecordSlot.MORNING.name()))
                .findFirst().orElseThrow();
        BillLineResponse evening = bill.lines().stream()
                .filter(line -> line.slot().equals(DailyRecordSlot.EVENING.name()))
                .findFirst().orElseThrow();
        assertThat(morning.quantity()).isEqualByComparingTo("2");
        assertThat(morning.unitPrice()).isEqualByComparingTo("60.00");
        assertThat(morning.amount()).isEqualByComparingTo("120.00");
        assertThat(evening.quantity()).isEqualByComparingTo("1.250");
        assertThat(evening.unitPrice()).isEqualByComparingTo("40.00");
        assertThat(evening.amount()).isEqualByComparingTo("50.00");
        assertThat(billRepository.findById(bill.id())).isPresent();
    }

    @Test
    void excludesPrepaidCustomersAndRejectsOpenPeriods() {
        User owner = owner();
        YearMonth prepaidMonth = YearMonth.of(2003, 2);
        Customer prepaid = customer(BillingType.PREPAID);
        Product product = product();
        createRecord(prepaid, product, prepaidMonth.atDay(1), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "60.00");
        billingPeriodService.closePeriod(prepaidMonth.toString(), owner.getId());

        assertThatThrownBy(() -> billService.generateDraft(prepaidMonth.toString(), prepaid.getId()))
                .isInstanceOf(BillConflictException.class)
                .hasMessageContaining("MONTHLY");

        Customer monthly = customer(BillingType.MONTHLY);
        YearMonth openMonth = YearMonth.of(2003, 3);
        assertThatThrownBy(() -> billService.generateDraft(openMonth.toString(), monthly.getId()))
                .isInstanceOf(BillConflictException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void rejectsEmptyAndZeroTotalPeriodsWithoutPersistingBills() {
        User owner = owner();
        Customer customer = customer(BillingType.MONTHLY);
        Product freeProduct = product();
        YearMonth emptyMonth = YearMonth.of(2003, 4);
        billingPeriodService.closePeriod(emptyMonth.toString(), owner.getId());
        assertThatThrownBy(() -> billService.generateDraft(emptyMonth.toString(), customer.getId()))
                .isInstanceOf(BillConflictException.class)
                .hasMessageContaining("non-zero");

        YearMonth zeroMonth = YearMonth.of(2003, 5);
        createRecord(customer, freeProduct, zeroMonth.atDay(1), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "0.00");
        billingPeriodService.closePeriod(zeroMonth.toString(), owner.getId());
        assertThatThrownBy(() -> billService.generateDraft(zeroMonth.toString(), customer.getId()))
                .isInstanceOf(BillConflictException.class)
                .hasMessageContaining("non-zero");
        assertThat(billRepository.findAll()).isEmpty();
    }

    @Test
    void duplicateGenerationReturnsTheExistingBillAndIssueIsIdempotent() {
        User owner = owner();
        Customer customer = customer(BillingType.MONTHLY);
        Product product = product();
        YearMonth month = YearMonth.of(2003, 6);
        DailyRecord record = createRecord(customer, product, month.atDay(1), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "60.00");
        billingPeriodService.closePeriod(month.toString(), owner.getId());

        BillResponse first = billService.generateDraft(month.toString(), customer.getId());
        customerService.updateCustomer(customer.getId(), new OwnerUpdateCustomerRequest(
                "Changed customer name", null, BillingType.PREPAID,
                CustomerStatus.INACTIVE, customer.getJoinDate()));
        BillResponse retry = billService.generateDraft(month.toString(), customer.getId());
        BillResponse issued = billService.issue(first.id(), owner.getId());
        BillResponse repeatedIssue = billService.issue(first.id(), owner.getId());

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.customerName()).isEqualTo(first.customerName());
        assertThat(issued.status()).isEqualTo(BillStatus.ISSUED);
        assertThat(issued.issuedAt()).isNotNull();
        assertThat(issued.issuedByUserId()).isEqualTo(owner.getId());
        assertThat(repeatedIssue.issuedAt()).isEqualTo(issued.issuedAt());
        assertThat(repeatedIssue.totalAmount()).isEqualByComparingTo(first.totalAmount());
        assertThat(repeatedIssue.lines().getFirst().dailyRecordId()).isEqualTo(record.getId());
        assertThatThrownBy(() -> dailyRecordService.updateDailyRecord(
                record.getId(),
                new OwnerUpdateDailyRecordRequest(
                        new BigDecimal("2"), DailyRecordStatus.COLLECTED, null)))
                .isInstanceOf(PeriodClosedException.class);
    }

    private User owner() {
        return userService.createUser(phone(), "secret123", UserRole.OWNER);
    }

    private Customer customer(BillingType billingType) {
        return customerService.createCustomer(new OwnerCreateCustomerRequest(
                phone(), "secret123", "Bill Customer " + UUID.randomUUID(), null,
                billingType, CustomerStatus.ACTIVE, LocalDate.now(businessClock)));
    }

    private Product product() {
        return productService.createProduct(new OwnerCreateProductRequest(
                "Bill Product " + UUID.randomUUID(), null, ProductUnit.LITRE, new BigDecimal("60.00")));
    }

    private DailyRecord createRecord(
            Customer customer,
            Product product,
            LocalDate date,
            DailyRecordStatus status,
            DailyRecordSlot slot,
            String quantity,
            String price
    ) {
        return dailyRecordService.createDailyRecord(new OwnerCreateDailyRecordRequest(
                customer.getId(), product.getId(), null, date, slot,
                new BigDecimal(quantity), price == null ? null : new BigDecimal(price), status));
    }

    private String phone() {
        return "98" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }
}
