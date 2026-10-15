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
import com.radhikafarms.milkweb.product.Product;
import com.radhikafarms.milkweb.product.ProductService;
import com.radhikafarms.milkweb.product.ProductUnit;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
 * Tests that need real commits: concurrent payments for one customer, and a refused reversal rolling back.
 * Uses its own in-memory database. H2 does not prove PostgreSQL locking; the customer row lock
 * (SELECT ... FOR UPDATE) behaves the same way on both for this pattern.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ledgerconcurrency;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class LedgerConcurrencyTest {

    @Autowired private LedgerService ledger;
    @Autowired private BillService billService;
    @Autowired private BillingPeriodService billingPeriodService;
    @Autowired private CustomerService customerService;
    @Autowired private ProductService productService;
    @Autowired private DailyRecordService dailyRecordService;
    @Autowired private UserService userService;
    @Autowired private BillAllocationRepository allocationRepository;
    @Autowired private PaymentReversalRepository reversalRepository;

    @Test
    void concurrentPaymentsNeverApplyMoreThanTheBillsAreWorth() throws Exception {
        UUID ownerId = userService.createUser(phone(), "secret123", UserRole.OWNER).getId();
        Customer customer = customer();
        issueBill(ownerId, customer, YearMonth.of(2006, 1), "300.00");
        issueBill(ownerId, customer, YearMonth.of(2006, 2), "300.00");

        int threads = 8;
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
        // 800 received against 600 of bills: both bills paid exactly, 200 held as credit, nothing double-applied.
        assertThat(account.totalReceived()).isEqualByComparingTo("800.00");
        assertThat(account.amountDue()).isEqualByComparingTo("0.00");
        assertThat(account.credit()).isEqualByComparingTo("200.00");
        assertThat(account.bills()).allSatisfy(bill -> assertThat(bill.paid()).isEqualByComparingTo("300.00"));
        assertThat(allocationRepository.sumNetForCustomer(customer.getId())).isEqualByComparingTo("600.00");
    }

    @Test
    void aRefusedReversalLeavesNoTrace() {
        UUID ownerId = userService.createUser(phone(), "secret123", UserRole.OWNER).getId();
        Customer customer = customer();
        var advance = ledger.recordPayment(customer.getId(), new RecordPaymentRequest(new BigDecimal("150.00"),
                PaymentMethod.CASH, null, null, null), UUID.randomUUID().toString(), ownerId);
        ledger.recordRefund(customer.getId(), new RecordRefundRequest(new BigDecimal("150.00"), PaymentMethod.CASH,
                "Customer moved away", null), UUID.randomUUID().toString(), ownerId);

        assertThatThrownBy(() -> ledger.reversePayment(advance.id(), new ReversePaymentRequest("Wrong amount"), ownerId))
                .isInstanceOf(LedgerConflictException.class)
                .hasMessageContaining("already returned");

        assertThat(reversalRepository.findByPaymentId(advance.id())).isEmpty();
        assertThat(ledger.totals(customer.getId()).credit()).isEqualByComparingTo("0.00");
    }

    private void issueBill(UUID ownerId, Customer customer, YearMonth month, String amount) {
        Product product = productService.createProduct(new OwnerCreateProductRequest(
                "Concurrency Product " + UUID.randomUUID(), null, ProductUnit.LITRE, new BigDecimal(amount)));
        dailyRecordService.createDailyRecord(new OwnerCreateDailyRecordRequest(customer.getId(), product.getId(), null,
                month.atDay(3), DailyRecordSlot.MORNING, BigDecimal.ONE, new BigDecimal(amount), DailyRecordStatus.COLLECTED));
        billingPeriodService.closePeriod(month.toString(), ownerId);
        billService.issue(billService.generateDraft(month.toString(), customer.getId()).id(), ownerId);
    }

    private Customer customer() {
        return customerService.createCustomer(new OwnerCreateCustomerRequest(phone(), "secret123",
                "Concurrent Customer", null, BillingType.MONTHLY, CustomerStatus.ACTIVE, LocalDate.of(2006, 1, 1)));
    }

    private static String phone() {
        return "95" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }
}
