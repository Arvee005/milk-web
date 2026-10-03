package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.billing.exception.PeriodClosedException;
import com.radhikafarms.milkweb.customer.*;
import com.radhikafarms.milkweb.dailyrecord.*;
import com.radhikafarms.milkweb.product.*;
import com.radhikafarms.milkweb.user.UserRepository;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import com.radhikafarms.milkweb.dailyrecord.exception.DailyRecordConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BillingPeriodConcurrencyTest {

    @Autowired
    private BillingPeriodService billingPeriodService;

    @Autowired
    private BillingPeriodRepository billingPeriodRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DailyRecordService dailyRecordService;

    @Autowired
    private DailyRecordRepository dailyRecordRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private Clock businessClock;

    @Test
    void concurrentPeriodInitializationAndCloseSerializeDailyRecordCreation() throws Exception {
        User owner = owner();
        Customer customer = customer();
        Product product = product();
        LocalDate periodDate = YearMonth.now(businessClock).minusMonths(1).atDay(15);
        LocalDate periodStart = periodDate.withDayOfMonth(1);
        String periodId = YearMonth.from(periodDate).toString();
        deletePeriod(periodStart);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        UUID createdRecordId = null;
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Outcome<DailyRecord>> create = executor.submit(() -> race(
                    ready, start,
                    () -> dailyRecordService.createDailyRecord(request(customer, product, periodDate))));
            Future<Outcome<BillingPeriodResponse>> close = executor.submit(() -> race(
                    ready, start,
                    () -> billingPeriodService.closePeriod(periodId, owner.getId())));
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            Outcome<DailyRecord> createOutcome = create.get(15, TimeUnit.SECONDS);
            Outcome<BillingPeriodResponse> closeOutcome = close.get(15, TimeUnit.SECONDS);
            assertThat(closeOutcome.error()).isNull();
            assertThat(closeOutcome.value().status()).isEqualTo(BillingPeriodStatus.CLOSED);

            if (createOutcome.error() == null) {
                createdRecordId = createOutcome.value().getId();
                assertThat(closeOutcome.value().chargeableRecordCount()).isEqualTo(1);
                assertThat(closeOutcome.value().chargeableAmount()).isEqualByComparingTo("60.00");
            } else {
                assertThat(createOutcome.error()).isInstanceOf(PeriodClosedException.class);
                assertThat(closeOutcome.value().chargeableRecordCount()).isZero();
            }
        } finally {
            executor.shutdownNow();
            if (createdRecordId != null) {
                dailyRecordRepository.deleteById(createdRecordId);
            }
            jdbcTemplate.update("delete from daily_records where customer_id = ?", customer.getId());
            deletePeriod(periodStart);
            cleanup(owner, customer, product);
        }
    }

    @Test
    void concurrentCloseAndPastUpdateDoNotChangeReconciliationSummary() throws Exception {
        User owner = owner();
        Customer customer = customer();
        Product product = product();
        LocalDate periodDate = YearMonth.now(businessClock).minusMonths(1).atDay(15);
        LocalDate periodStart = periodDate.withDayOfMonth(1);
        String periodId = YearMonth.from(periodDate).toString();
        deletePeriod(periodStart);
        DailyRecord record = dailyRecordService.createDailyRecord(request(customer, product, periodDate));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Outcome<DailyRecord>> update = executor.submit(() -> race(
                    ready, start,
                    () -> dailyRecordService.updateDailyRecord(
                            record.getId(),
                            new OwnerUpdateDailyRecordRequest(
                                    new BigDecimal("2"), DailyRecordStatus.COLLECTED, null))));
            Future<Outcome<BillingPeriodResponse>> close = executor.submit(() -> race(
                    ready, start,
                    () -> billingPeriodService.closePeriod(periodId, owner.getId())));
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            Outcome<DailyRecord> updateOutcome = update.get(15, TimeUnit.SECONDS);
            Outcome<BillingPeriodResponse> closeOutcome = close.get(15, TimeUnit.SECONDS);
            assertThat(closeOutcome.error()).isNull();
            assertThat(closeOutcome.value().status()).isEqualTo(BillingPeriodStatus.CLOSED);

            assertThat(updateOutcome.error()).isInstanceOfAny(
                    PeriodClosedException.class, DailyRecordConflictException.class);
            assertThat(closeOutcome.value().chargeableAmount()).isEqualByComparingTo("60.00");
        } finally {
            executor.shutdownNow();
            jdbcTemplate.update("delete from daily_records where customer_id = ?", customer.getId());
            deletePeriod(periodStart);
            cleanup(owner, customer, product);
        }
    }

    private <T> Outcome<T> race(
            CountDownLatch ready,
            CountDownLatch start,
            Callable<T> action
    ) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                return new Outcome<>(null, new IllegalStateException("Race start timed out"));
            }
            return new Outcome<>(action.call(), null);
        } catch (Exception ex) {
            return new Outcome<>(null, ex);
        }
    }

    private User owner() {
        return userService.createUser(
                "91" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000),
                "secret123",
                UserRole.OWNER);
    }

    private Customer customer() {
        return customerService.createCustomer(new OwnerCreateCustomerRequest(
                "92" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000),
                "secret123", "Billing Race Customer", null, BillingType.MONTHLY,
                CustomerStatus.ACTIVE, LocalDate.now(businessClock)));
    }

    private Product product() {
        return productService.createProduct(new OwnerCreateProductRequest(
                "Billing Race Product " + UUID.randomUUID(), null, ProductUnit.LITRE, new BigDecimal("60.00")));
    }

    private OwnerCreateDailyRecordRequest request(Customer customer, Product product, LocalDate date) {
        return new OwnerCreateDailyRecordRequest(
                customer.getId(), product.getId(), null, date, DailyRecordSlot.MORNING,
                BigDecimal.ONE, new BigDecimal("60.00"), DailyRecordStatus.COLLECTED);
    }

    private void deletePeriod(LocalDate periodStart) {
        jdbcTemplate.update("delete from billing_periods where period_start = ?", periodStart);
    }

    private void cleanup(User owner, Customer customer, Product product) {
        jdbcTemplate.update("delete from daily_records where customer_id = ?", customer.getId());
        customerRepository.deleteById(customer.getId());
        productRepository.deleteById(product.getId());
        userRepository.deleteById(customer.getUser().getId());
        userRepository.deleteById(owner.getId());
    }

    private record Outcome<T>(T value, Throwable error) {
    }
}
