package com.radhikafarms.milkweb.billing;

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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BillingPeriodServiceTest {

    @Autowired
    private BillingPeriodService billingPeriodService;

    @Autowired
    private BillingPeriodRepository billingPeriodRepository;

    @Autowired
    private DailyRecordService dailyRecordService;

    @Autowired
    private DailyRecordRepository dailyRecordRepository;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ProductService productService;

    @Autowired
    private UserService userService;

    @Autowired
    private Clock businessClock;

    @Test
    void lookupDoesNotCreatePeriodAndUsesCanonicalLeapYearDates() {
        BillingPeriodResponse response = billingPeriodService.getPeriod("2024-02");

        assertThat(response.periodStart()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(response.periodEnd()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(response.status()).isEqualTo(BillingPeriodStatus.OPEN);
        assertThat(response.createdAt()).isNull();
        assertThat(response.chargeableRecordCount()).isZero();
        assertThat(response.chargeableAmount()).isEqualByComparingTo("0.00");
        assertThat(billingPeriodRepository.existsByPeriodStart(LocalDate.of(2024, 2, 1))).isFalse();
    }

    @Test
    void rejectsInvalidPeriodIdentifiers() {
        for (String identifier : new String[]{"2024-00", "2024-13", "24-01", "2024-2", "0000-01"}) {
            assertThatThrownBy(() -> billingPeriodService.getPeriod(identifier))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void createsCanonicalPeriodAndClosesItWithChargeableSummary() {
        User owner = owner();
        Customer customer = customer();
        Product product = product();
        YearMonth month = YearMonth.of(2000, 2);
        LocalDate firstDay = month.atDay(1);

        DailyRecord collected = dailyRecordService.createDailyRecord(request(
                customer, product, firstDay, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "2", "60.00"));
        DailyRecord extra = dailyRecordService.createDailyRecord(request(
                customer, product(), firstDay, DailyRecordStatus.EXTRA,
                DailyRecordSlot.EVENING, "1.5", "40.00"));
        DailyRecord skipped = dailyRecordService.createDailyRecord(request(
                customer, product(), firstDay.plusDays(1), DailyRecordStatus.SKIPPED,
                DailyRecordSlot.MORNING, "0", null));
        DailyRecord paused = dailyRecordService.createDailyRecord(request(
                customer, product(), firstDay.plusDays(1), DailyRecordStatus.PAUSED,
                DailyRecordSlot.EVENING, "0", null));

        BillingPeriodResponse closed = billingPeriodService.closePeriod(month.toString(), owner.getId());

        assertThat(closed.periodStart()).isEqualTo(month.atDay(1));
        assertThat(closed.periodEnd()).isEqualTo(month.atEndOfMonth());
        assertThat(closed.status()).isEqualTo(BillingPeriodStatus.CLOSED);
        assertThat(closed.createdAt()).isNotNull();
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.closedByUserId()).isEqualTo(owner.getId());
        assertThat(closed.chargeableRecordCount()).isEqualTo(2);
        assertThat(closed.chargeableAmount()).isEqualByComparingTo("180.00");

        BillingPeriod persisted = billingPeriodRepository.findByPeriodStart(month.atDay(1)).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(BillingPeriodStatus.CLOSED);
        assertThat(persisted.getClosedBy().getId()).isEqualTo(owner.getId());
        assertThat(dailyRecordService.getDailyRecordById(collected.getId()).getAmount())
                .isEqualByComparingTo("120.00");
        assertThat(dailyRecordService.getDailyRecordById(extra.getId()).getAmount())
                .isEqualByComparingTo("60.00");
        assertThat(dailyRecordService.getDailyRecordById(skipped.getId()).getStatus())
                .isEqualTo(DailyRecordStatus.SKIPPED);
        assertThat(dailyRecordService.getDailyRecordById(paused.getId()).getStatus())
                .isEqualTo(DailyRecordStatus.PAUSED);
    }

    @Test
    void closesEmptyPeriodAndRepeatedCloseReturnsOriginalClosure() {
        User owner = owner();
        String monthId = "2001-07";

        BillingPeriodResponse first = billingPeriodService.closePeriod(monthId, owner.getId());
        BillingPeriodResponse repeated = billingPeriodService.closePeriod(monthId, owner.getId());

        assertThat(first.status()).isEqualTo(BillingPeriodStatus.CLOSED);
        assertThat(first.chargeableRecordCount()).isZero();
        assertThat(first.chargeableAmount()).isEqualByComparingTo("0.00");
        assertThat(repeated.closedAt()).isEqualTo(first.closedAt());
        assertThat(repeated.closedByUserId()).isEqualTo(first.closedByUserId());
    }

    @Test
    void rejectsClosingCurrentAndFuturePeriodsButAllowsInspection() {
        YearMonth currentMonth = YearMonth.now(businessClock);
        User owner = owner();

        assertThatThrownBy(() -> billingPeriodService.closePeriod(
                currentMonth.toString(), owner.getId()))
                .isInstanceOf(com.radhikafarms.milkweb.billing.exception.BillingPeriodConflictException.class)
                .hasMessageContaining("completed");
        assertThatThrownBy(() -> billingPeriodService.closePeriod(
                currentMonth.plusMonths(1).toString(), owner.getId()))
                .isInstanceOf(com.radhikafarms.milkweb.billing.exception.BillingPeriodConflictException.class)
                .hasMessageContaining("completed");

        BillingPeriodResponse current = billingPeriodService.getPeriod(currentMonth.toString());
        BillingPeriodResponse future = billingPeriodService.getPeriod(currentMonth.plusMonths(1).toString());
        assertThat(current.status()).isEqualTo(BillingPeriodStatus.OPEN);
        assertThat(future.status()).isEqualTo(BillingPeriodStatus.OPEN);
        assertThat(billingPeriodRepository.existsByPeriodStart(currentMonth.plusMonths(1).atDay(1))).isFalse();
    }

    @Test
    void closedPeriodBlocksNewAndBackdatedRecordsAndExistingRecordUpdates() {
        User owner = owner();
        Customer customer = customer();
        Product product = product();
        LocalDate closedMonthDate = YearMonth.now(businessClock).minusMonths(1).atDay(15);
        DailyRecord existing = dailyRecordService.createDailyRecord(request(
                customer, product, closedMonthDate, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "60.00"));

        billingPeriodService.closePeriod(YearMonth.from(closedMonthDate).toString(), owner.getId());

        assertThatThrownBy(() -> dailyRecordService.createDailyRecord(request(
                customer, product(), closedMonthDate.minusDays(1), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.EVENING, "1", "60.00")))
                .isInstanceOf(PeriodClosedException.class);
        assertThatThrownBy(() -> dailyRecordService.updateDailyRecord(existing.getId(),
                new OwnerUpdateDailyRecordRequest(new BigDecimal("2"), DailyRecordStatus.COLLECTED, null)))
                .isInstanceOf(PeriodClosedException.class);
    }

    @Test
    void sameDayCorrectionRemainsAvailableWhileItsPeriodIsOpen() {
        Customer customer = customer();
        Product product = product();
        DailyRecord record = dailyRecordService.createDailyRecord(request(
                customer, product, LocalDate.now(businessClock), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "60.00"));

        DailyRecord updated = dailyRecordService.updateDailyRecord(record.getId(),
                new OwnerUpdateDailyRecordRequest(new BigDecimal("1.5"), DailyRecordStatus.COLLECTED, null));

        assertThat(updated.getQuantity()).isEqualByComparingTo("1.5");
        assertThat(updated.getAmount()).isEqualByComparingTo("90.00");

        BillingPeriodResponse openSummary = billingPeriodService.getPeriod(
                YearMonth.from(LocalDate.now(businessClock)).toString());
        assertThat(openSummary.status()).isEqualTo(BillingPeriodStatus.OPEN);
        assertThat(openSummary.chargeableRecordCount()).isEqualTo(1);
        assertThat(openSummary.chargeableAmount()).isEqualByComparingTo("90.00");
    }

    private User owner() {
        return userService.createUser(
                "96" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000),
                "secret123",
                UserRole.OWNER);
    }

    private Customer customer() {
        return customerService.createCustomer(new OwnerCreateCustomerRequest(
                "97" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000),
                "secret123", "Billing Customer", null, BillingType.MONTHLY,
                CustomerStatus.ACTIVE, LocalDate.now(businessClock)));
    }

    private Product product() {
        return productService.createProduct(new OwnerCreateProductRequest(
                "Billing Product " + UUID.randomUUID(), null, ProductUnit.LITRE, new BigDecimal("60.00")));
    }

    private OwnerCreateDailyRecordRequest request(
            Customer customer,
            Product product,
            LocalDate date,
            DailyRecordStatus status,
            DailyRecordSlot slot,
            String quantity,
            String price
    ) {
        return new OwnerCreateDailyRecordRequest(
                customer.getId(), product.getId(), null, date, slot,
                new BigDecimal(quantity), price == null ? null : new BigDecimal(price), status);
    }
}
