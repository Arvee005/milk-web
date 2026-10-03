package com.radhikafarms.milkweb.dailyrecord;

import com.radhikafarms.milkweb.customer.*;
import com.radhikafarms.milkweb.customer.exception.CustomerNotFoundException;
import com.radhikafarms.milkweb.dailyrecord.exception.DailyRecordConflictException;
import com.radhikafarms.milkweb.dailyrecord.exception.DailyRecordNotFoundException;
import com.radhikafarms.milkweb.dailyrecord.exception.DuplicateDailyRecordException;
import com.radhikafarms.milkweb.product.*;
import com.radhikafarms.milkweb.product.exception.ProductNotFoundException;
import com.radhikafarms.milkweb.subscription.*;
import com.radhikafarms.milkweb.subscription.exception.SubscriptionNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class DailyRecordServiceTest {

    @Autowired
    private DailyRecordService dailyRecordService;

    @Autowired
    private Clock businessClock;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Test
    void createsCollectedRecordAndCalculatesAmountExactly() {
        Customer customer = customer();
        Product product = product();
        LocalDate today = LocalDate.now(businessClock);

        DailyRecord record = dailyRecordService.createDailyRecord(
                request(customer, product, null, today, DailyRecordStatus.COLLECTED,
                        DailyRecordSlot.MORNING, "2.500", "60.00"));

        assertThat(record.getId()).isNotNull();
        assertThat(record.getCustomer().getId()).isEqualTo(customer.getId());
        assertThat(record.getProduct().getId()).isEqualTo(product.getId());
        assertThat(record.getSubscription()).isNull();
        assertThat(record.getRecordDate()).isEqualTo(today);
        assertThat(record.getSlot()).isEqualTo(DailyRecordSlot.MORNING);
        assertThat(record.getQuantity()).isEqualByComparingTo("2.500");
        assertThat(record.getUnitPriceSnapshot()).isEqualByComparingTo("60.00");
        assertThat(record.getAmount()).isEqualByComparingTo("150.00");
        assertThat(record.getStatus()).isEqualTo(DailyRecordStatus.COLLECTED);
        assertThat(record.getCreatedAt()).isNotNull();
        assertThat(record.getUpdatedAt()).isNotNull();
    }

    @Test
    void createsExtraSkippedAndPausedEventsWithConditionalValues() {
        Customer customer = customer();
        Product product = product();
        LocalDate today = LocalDate.now(businessClock);

        DailyRecord extra = dailyRecordService.createDailyRecord(request(
                customer, product, null, today, DailyRecordStatus.EXTRA,
                DailyRecordSlot.MORNING, "3.000", "60.00"));
        DailyRecord skipped = dailyRecordService.createDailyRecord(request(
                customer, product(), null, today, DailyRecordStatus.SKIPPED,
                DailyRecordSlot.EVENING, "0", null));
        DailyRecord paused = dailyRecordService.createDailyRecord(request(
                customer, product(), null, today, DailyRecordStatus.PAUSED,
                DailyRecordSlot.MORNING, "0.000", null));

        assertThat(extra.getQuantity()).isEqualByComparingTo("3.000");
        assertThat(extra.getAmount()).isEqualByComparingTo("180.00");
        assertThat(skipped.getQuantity()).isEqualByComparingTo("0");
        assertThat(skipped.getUnitPriceSnapshot()).isNull();
        assertThat(skipped.getAmount()).isEqualByComparingTo("0.00");
        assertThat(paused.getStatus()).isEqualTo(DailyRecordStatus.PAUSED);
        assertThat(paused.getAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void roundsFinalAmountHalfUpOnce() {
        DailyRecord record = dailyRecordService.createDailyRecord(request(
                customer(), product(), null, LocalDate.now(), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "0.005", "1.00"));
        assertThat(record.getAmount()).isEqualByComparingTo("0.01");
    }

    @Test
    void rejectsInvalidQuantityAndPriceCombinations() {
        Customer customer = customer();
        Product product = product();
        assertConflict(request(customer, product, null, LocalDate.now(), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "0", "1.00"));
        assertConflict(request(customer, product, null, LocalDate.now(), DailyRecordStatus.EXTRA,
                DailyRecordSlot.MORNING, "-1", "1.00"));
        assertConflict(request(customer, product, null, LocalDate.now(), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", null));
        assertConflict(request(customer, product, null, LocalDate.now(), DailyRecordStatus.SKIPPED,
                DailyRecordSlot.MORNING, "1", null));
        assertConflict(request(customer, product, null, LocalDate.now(), DailyRecordStatus.PAUSED,
                DailyRecordSlot.MORNING, "0", "1.00"));
        assertConflict(request(customer, product, null, LocalDate.now(), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1.0001", "1.00"));
        assertConflict(request(customer, product, null, LocalDate.now(), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "-0.01"));
        assertConflict(request(customer, product, null, LocalDate.now(), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "1.001"));
        assertConflict(request(customer, product, null, LocalDate.now(), DailyRecordStatus.SKIPPED,
                DailyRecordSlot.MORNING, "0", "0.00"));
    }

    @Test
    void allowsPastDateBackfillAndRequiresExplicitPriceForChargeableRecord() {
        Customer customer = customer();
        Product product = product();
        LocalDate past = LocalDate.now().minusDays(5);

        DailyRecord created = dailyRecordService.createDailyRecord(request(
                customer, product, null, past, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1.250", "55.00"));
        assertThat(created.getRecordDate()).isEqualTo(past);
        assertThat(created.getAmount()).isEqualByComparingTo("68.75");
        assertConflict(request(customer, product(), null, past, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", null));
    }

    @Test
    void validatesOptionalSubscriptionOwnershipProductAndInclusiveDates() {
        Customer customer = customer();
        Product product = product();
        LocalDate start = LocalDate.now().minusDays(2);
        LocalDate end = LocalDate.now().minusDays(1);
        Subscription subscription = subscription(customer, product, start, end);

        DailyRecord matching = dailyRecordService.createDailyRecord(request(
                customer, product, subscription, end, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1.5", "60.00"));
        assertThat(matching.getSubscription().getId()).isEqualTo(subscription.getId());

        assertThatThrownBy(() -> dailyRecordService.createDailyRecord(requestWithSubscriptionId(
                customer, product, UUID.randomUUID(), end, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.EVENING, "1", "60.00")))
                .isInstanceOf(SubscriptionNotFoundException.class);

        Customer otherCustomer = customer();
        assertConflict(request(otherCustomer, product, subscription, end, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.EVENING, "1", "60.00"));

        Product otherProduct = product();
        assertConflict(request(customer, otherProduct, subscription, end, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.EVENING, "1", "60.00"));

        assertConflict(request(customer, product, subscription, start.minusDays(1),
                DailyRecordStatus.COLLECTED, DailyRecordSlot.EVENING, "1", "60.00"));
        assertConflict(request(customer, product, subscription, end.plusDays(1),
                DailyRecordStatus.COLLECTED, DailyRecordSlot.EVENING, "1", "60.00"));
    }

    @Test
    void permitsHistoricalPausedOrCancelledSubscriptionReferencesWithoutMutatingPlan() {
        Customer customer = customer();
        Product product = product();
        LocalDate today = LocalDate.now();
        Subscription subscription = subscription(customer, product, today, null);
        subscription.setStatus(SubscriptionStatus.PAUSED);
        subscriptionRepository.save(subscription);
        BigDecimal originalQuantity = subscription.getDefaultQuantity();

        DailyRecord record = dailyRecordService.createDailyRecord(request(
                customer, product, subscription, today, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1.250", "60.00"));

        assertThat(record.getSubscription().getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
        assertThat(subscription.getDefaultQuantity()).isEqualByComparingTo(originalQuantity);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);

        Product cancelledProduct = product();
        Subscription cancelled = subscription(customer, cancelledProduct, today, null);
        cancelled.setStatus(SubscriptionStatus.CANCELLED);
        subscriptionRepository.save(cancelled);
        DailyRecord cancelledPlanRecord = dailyRecordService.createDailyRecord(request(
                customer, cancelledProduct, cancelled, today, DailyRecordStatus.EXTRA,
                DailyRecordSlot.EVENING, "1.000", "60.00"));
        assertThat(cancelledPlanRecord.getSubscription().getStatus())
                .isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    void rejectsUnknownAndIneligibleCustomerOrProductForPositiveSupply() {
        Customer customer = customer();
        Product product = product();
        assertThatThrownBy(() -> dailyRecordService.createDailyRecord(request(
                UUID.randomUUID(), product.getId(), null, LocalDate.now(), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "60.00")))
                .isInstanceOf(CustomerNotFoundException.class);
        assertThatThrownBy(() -> dailyRecordService.createDailyRecord(request(
                customer.getId(), UUID.randomUUID(), null, LocalDate.now(), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "60.00")))
                .isInstanceOf(ProductNotFoundException.class);

        customer.setStatus(CustomerStatus.PAUSED);
        customerRepository.save(customer);
        assertConflict(request(customer, product, null, LocalDate.now(), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "60.00"));
        assertThat(dailyRecordService.createDailyRecord(request(
                customer, product(), null, LocalDate.now(), DailyRecordStatus.SKIPPED,
                DailyRecordSlot.EVENING, "0", null))).isNotNull();

        Customer inactiveCustomer = customer();
        inactiveCustomer.setStatus(CustomerStatus.INACTIVE);
        customerRepository.save(inactiveCustomer);
        assertConflict(request(inactiveCustomer, product(), null, LocalDate.now(),
                DailyRecordStatus.EXTRA, DailyRecordSlot.MORNING, "1", "60.00"));

        Customer activeCustomer = customer();
        Product inactiveProduct = product();
        inactiveProduct.setStatus(ProductStatus.INACTIVE);
        productRepository.save(inactiveProduct);
        assertConflict(request(activeCustomer, inactiveProduct, null, LocalDate.now(),
                DailyRecordStatus.EXTRA, DailyRecordSlot.MORNING, "1", "60.00"));
    }

    @Test
    void duplicateEventIsRejectedForAllStatusesIncludingExtra() {
        for (DailyRecordStatus status : DailyRecordStatus.values()) {
            Customer customer = customer();
            Product product = product();
            String quantity = status == DailyRecordStatus.SKIPPED || status == DailyRecordStatus.PAUSED
                    ? "0" : "1";
            String price = status == DailyRecordStatus.SKIPPED || status == DailyRecordStatus.PAUSED
                    ? null : "60.00";
            OwnerCreateDailyRecordRequest request = request(
                    customer, product, null, LocalDate.now(), status, DailyRecordSlot.MORNING, quantity, price);
            dailyRecordService.createDailyRecord(request);
            assertThatThrownBy(() -> dailyRecordService.createDailyRecord(request))
                    .isInstanceOf(DuplicateDailyRecordException.class);
        }
    }

    @Test
    void productPriceChangesDoNotAlterStoredSnapshotOrAmount() {
        Customer customer = customer();
        Product product = product();
        DailyRecord record = dailyRecordService.createDailyRecord(request(
                customer, product, null, LocalDate.now(), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "2.500", "60.00"));

        product.setPrice(new BigDecimal("65.00"));
        productRepository.saveAndFlush(product);

        DailyRecord reloaded = dailyRecordService.getDailyRecordById(record.getId());
        assertThat(reloaded.getUnitPriceSnapshot()).isEqualByComparingTo("60.00");
        assertThat(reloaded.getAmount()).isEqualByComparingTo("150.00");
    }

    @Test
    void correctsTodaysQuantityAndStatusWithoutChangingExistingPrice() {
        Customer customer = customer();
        Product product = product();
        LocalDate today = LocalDate.now(businessClock);
        DailyRecord record = dailyRecordService.createDailyRecord(request(
                customer, product, null, today, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "2", "60.00"));

        DailyRecord corrected = dailyRecordService.updateDailyRecord(record.getId(),
                new OwnerUpdateDailyRecordRequest(new BigDecimal("1.500"),
                        DailyRecordStatus.EXTRA, null));
        assertThat(corrected.getQuantity()).isEqualByComparingTo("1.500");
        assertThat(corrected.getUnitPriceSnapshot()).isEqualByComparingTo("60.00");
        assertThat(corrected.getAmount()).isEqualByComparingTo("90.00");
        assertThat(corrected.getStatus()).isEqualTo(DailyRecordStatus.EXTRA);

        DailyRecord skipped = dailyRecordService.updateDailyRecord(record.getId(),
                new OwnerUpdateDailyRecordRequest(BigDecimal.ZERO, DailyRecordStatus.SKIPPED, null));
        assertThat(skipped.getUnitPriceSnapshot()).isNull();
        assertThat(skipped.getAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void changesNoSupplyRecordToChargeableOnlyWithImmutablePriceSnapshot() {
        Customer customer = customer();
        Product product = product();
        DailyRecord skipped = dailyRecordService.createDailyRecord(request(
                customer, product, null, LocalDate.now(businessClock), DailyRecordStatus.SKIPPED,
                DailyRecordSlot.MORNING, "0", null));

        assertThatThrownBy(() -> dailyRecordService.updateDailyRecord(skipped.getId(),
                new OwnerUpdateDailyRecordRequest(BigDecimal.ONE, DailyRecordStatus.COLLECTED, null)))
                .isInstanceOf(DailyRecordConflictException.class)
                .hasMessageContaining("required");

        DailyRecord supplied = dailyRecordService.updateDailyRecord(skipped.getId(),
                new OwnerUpdateDailyRecordRequest(BigDecimal.ONE, DailyRecordStatus.COLLECTED,
                        new BigDecimal("60.00")));
        assertThat(supplied.getUnitPriceSnapshot()).isEqualByComparingTo("60.00");
        assertThat(supplied.getAmount()).isEqualByComparingTo("60.00");
        assertThatThrownBy(() -> dailyRecordService.updateDailyRecord(skipped.getId(),
                new OwnerUpdateDailyRecordRequest(BigDecimal.ONE, DailyRecordStatus.COLLECTED,
                        new BigDecimal("61.00"))))
                .isInstanceOf(DailyRecordConflictException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    void rejectsPastUpdatesAndUnknownRecords() {
        Customer customer = customer();
        Product product = product();
        DailyRecord past = dailyRecordService.createDailyRecord(request(
                customer, product, null, LocalDate.now().minusDays(1), DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "60.00"));
        assertThatThrownBy(() -> dailyRecordService.updateDailyRecord(past.getId(),
                new OwnerUpdateDailyRecordRequest(BigDecimal.ONE, DailyRecordStatus.COLLECTED, null)))
                .isInstanceOf(DailyRecordConflictException.class)
                .hasMessageContaining("Past");
        assertThatThrownBy(() -> dailyRecordService.getDailyRecordById(UUID.randomUUID()))
                .isInstanceOf(DailyRecordNotFoundException.class);
    }

    @Test
    void listsWithFiltersAndPagination() {
        Customer customer = customer();
        Product product = product();
        LocalDate day = LocalDate.now().minusDays(1);
        DailyRecord first = dailyRecordService.createDailyRecord(request(
                customer, product, null, day, DailyRecordStatus.COLLECTED,
                DailyRecordSlot.MORNING, "1", "60.00"));
        DailyRecord second = dailyRecordService.createDailyRecord(request(
                customer, product, null, day, DailyRecordStatus.SKIPPED,
                DailyRecordSlot.EVENING, "0", null));
        var page = dailyRecordService.listDailyRecords(
                customer.getId(), product.getId(), null, day, day, null, null,
                PageRequest.of(0, 1, Sort.by(Sort.Order.desc("recordDate"), Sort.Order.asc("slot"))));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getId()).isEqualTo(second.getId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    private Customer customer() {
        String phone = "95" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
        return customerService.createCustomer(new OwnerCreateCustomerRequest(
                phone, "secret123", "Daily Customer", null, BillingType.MONTHLY,
                CustomerStatus.ACTIVE, LocalDate.now()));
    }

    private Product product() {
        return productService.createProduct(new OwnerCreateProductRequest(
                "Daily Product " + UUID.randomUUID(), null, ProductUnit.LITRE, new BigDecimal("60.00")));
    }

    private Subscription subscription(Customer customer, Product product, LocalDate start, LocalDate end) {
        return subscriptionService.createSubscription(new OwnerCreateSubscriptionRequest(
                customer.getId(), product.getId(), new BigDecimal("2.000"), start, end));
    }

    private OwnerCreateDailyRecordRequest request(
            Customer customer, Product product, Subscription subscription, LocalDate date,
            DailyRecordStatus status, DailyRecordSlot slot, String quantity, String price
    ) {
        return request(customer.getId(), product.getId(),
                subscription == null ? null : subscription.getId(), date, status, slot, quantity, price);
    }

    private OwnerCreateDailyRecordRequest requestWithSubscriptionId(
            Customer customer, Product product, UUID subscriptionId, LocalDate date,
            DailyRecordStatus status, DailyRecordSlot slot, String quantity, String price
    ) {
        return request(customer.getId(), product.getId(), subscriptionId, date,
                status, slot, quantity, price);
    }

    private OwnerCreateDailyRecordRequest request(
            UUID customerId, UUID productId, UUID subscriptionId, LocalDate date,
            DailyRecordStatus status, DailyRecordSlot slot, String quantity, String price
    ) {
        return new OwnerCreateDailyRecordRequest(
                customerId, productId, subscriptionId, date, slot,
                quantity == null ? null : new BigDecimal(quantity),
                price == null ? null : new BigDecimal(price),
                status);
    }

    private void assertConflict(OwnerCreateDailyRecordRequest request) {
        assertThatThrownBy(() -> dailyRecordService.createDailyRecord(request))
                .isInstanceOf(DailyRecordConflictException.class);
    }
}
