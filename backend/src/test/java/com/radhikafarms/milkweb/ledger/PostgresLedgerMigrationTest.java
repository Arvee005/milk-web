package com.radhikafarms.milkweb.ledger;

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
import com.radhikafarms.milkweb.product.OwnerCreateProductRequest;
import com.radhikafarms.milkweb.product.ProductService;
import com.radhikafarms.milkweb.product.ProductUnit;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real Flyway migrations (V1-V4) on PostgreSQL and lets Hibernate validate the schema, exactly as the
 * prod profile does. Skipped when Docker is not available.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
class PostgresLedgerMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LedgerService ledger;
    @Autowired private UserService userService;
    @Autowired private CustomerService customerService;
    @Autowired private ProductService productService;
    @Autowired private DailyRecordService dailyRecordService;
    @Autowired private BillingPeriodService billingPeriodService;
    @Autowired private BillService billService;

    @Test
    void migrationsApplyAndTheDatabaseEnforcesLedgerRules() {
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank",
                String.class)).containsExactly("1", "2", "3", "4");

        UUID ownerId = userService.createUser(phone(), "secret123", UserRole.OWNER).getId();
        Customer customer = customer();
        var payment = ledger.recordPayment(customer.getId(), new RecordPaymentRequest(new BigDecimal("75.50"),
                PaymentMethod.UPI, "UTR1", null, null), UUID.randomUUID().toString(), ownerId);
        assertThat(jdbc.queryForObject("select amount from payments where id = ?", BigDecimal.class, payment.id()))
                .isEqualByComparingTo("75.50");

        // The constraints in V4 hold even if application checks were bypassed.
        assertThatThrownBy(() -> jdbc.update("update payments set currency = 'USD' where id = ?", payment.id()))
                .hasMessageContaining("ck_payments_currency");
        assertThatThrownBy(() -> jdbc.update("""
                insert into account_adjustments (id, customer_id, bill_id, direction, amount, currency, reason,
                    recorded_at, recorded_by, idempotency_key)
                values (gen_random_uuid(), ?, null, 'DEBIT', 10, 'INR', 'x', now(), ?, 'pg-test-debit-key')
                """, customer.getId(), ownerId))
                .hasMessageContaining("ck_account_adjustments_scope");
        assertThatThrownBy(() -> jdbc.update("""
                insert into refunds (id, customer_id, amount, currency, method, reason, refunded_on, recorded_at,
                    recorded_by, idempotency_key)
                values (gen_random_uuid(), ?, 0, 'INR', 'CASH', 'x', current_date, now(), ?, 'pg-test-refund-key')
                """, customer.getId(), ownerId))
                .hasMessageContaining("ck_refunds_amount_positive");
    }

    @Test
    void concurrentPaymentsAreSerialisedByTheCustomerLock() throws Exception {
        UUID ownerId = userService.createUser(phone(), "secret123", UserRole.OWNER).getId();
        Customer customer = customer();
        var product = productService.createProduct(new OwnerCreateProductRequest(
                "PG Product " + UUID.randomUUID(), null, ProductUnit.LITRE, new BigDecimal("300.00")));
        dailyRecordService.createDailyRecord(new OwnerCreateDailyRecordRequest(customer.getId(), product.getId(), null,
                LocalDate.of(2008, 1, 5), DailyRecordSlot.MORNING, BigDecimal.ONE, new BigDecimal("300.00"),
                DailyRecordStatus.COLLECTED));
        billingPeriodService.closePeriod("2008-01", ownerId);
        billService.issue(billService.generateDraft("2008-01", customer.getId()).id(), ownerId);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ledger.recordPayment(customer.getId(), new RecordPaymentRequest(new BigDecimal("100.00"),
                            PaymentMethod.CASH, null, null, null), UUID.randomUUID().toString(), ownerId);
                } catch (Throwable error) {
                    failures.add(error);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        var account = ledger.account(customer.getId());
        assertThat(account.bills().getFirst().paid()).isEqualByComparingTo("300.00");
        assertThat(account.credit()).isEqualByComparingTo("300.00");
    }

    private Customer customer() {
        return customerService.createCustomer(new OwnerCreateCustomerRequest(phone(), "secret123", "PG Customer",
                null, BillingType.MONTHLY, CustomerStatus.ACTIVE, LocalDate.of(2008, 1, 1)));
    }

    private static String phone() {
        return "93" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }
}
