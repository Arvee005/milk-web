package com.radhikafarms.milkweb.subscription;

import com.radhikafarms.milkweb.customer.*;
import com.radhikafarms.milkweb.customer.exception.CustomerNotFoundException;
import com.radhikafarms.milkweb.product.*;
import com.radhikafarms.milkweb.product.exception.ProductNotFoundException;
import com.radhikafarms.milkweb.subscription.exception.OverlappingSubscriptionException;
import com.radhikafarms.milkweb.subscription.exception.SubscriptionConflictException;
import com.radhikafarms.milkweb.subscription.exception.SubscriptionNotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class SubscriptionServiceTest {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void createsActiveSubscriptionWithExactValuesAndRelationships() {
        Customer customer = createCustomer("9800000001");
        Product product = createProduct("Service Cow Milk");
        LocalDate start = LocalDate.now().plusDays(10);
        LocalDate end = start.plusDays(30);

        Subscription created = subscriptionService.createSubscription(
                createRequest(customer.getId(), product.getId(), "10.125", start, end));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCustomer().getId()).isEqualTo(customer.getId());
        assertThat(created.getProduct().getId()).isEqualTo(product.getId());
        assertThat(created.getDefaultQuantity()).isEqualByComparingTo("10.125");
        assertThat(created.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(created.getStartDate()).isEqualTo(start);
        assertThat(created.getEndDate()).isEqualTo(end);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsMissingAndInactiveRelatedEntities() {
        Product product = createProduct("Eligibility Milk");
        assertThatThrownBy(() -> subscriptionService.createSubscription(
                createRequest(UUID.randomUUID(), product.getId(), "1", LocalDate.now(), null)))
                .isInstanceOf(CustomerNotFoundException.class);

        Customer customer = createCustomer("9800000002");
        assertThatThrownBy(() -> subscriptionService.createSubscription(
                createRequest(customer.getId(), UUID.randomUUID(), "1", LocalDate.now(), null)))
                .isInstanceOf(ProductNotFoundException.class);

        customer.setStatus(CustomerStatus.PAUSED);
        customerRepository.save(customer);
        assertThatThrownBy(() -> subscriptionService.createSubscription(
                createRequest(customer.getId(), product.getId(), "1", LocalDate.now(), null)))
                .isInstanceOf(SubscriptionConflictException.class)
                .hasMessageContaining("Customer");
    }

    @Test
    void rejectsInactiveProductAndAllowsExistingSubscriptionToRemainUnchanged() {
        Customer customer = createCustomer("9800000003");
        Product product = createProduct("Inactive Milk");
        productService.updateProduct(product.getId(), new OwnerUpdateProductRequest(
                product.getName(), product.getDescription(), product.getUnit(),
                product.getPrice(), ProductStatus.INACTIVE));

        assertThatThrownBy(() -> subscriptionService.createSubscription(
                createRequest(customer.getId(), product.getId(), "1", LocalDate.now(), null)))
                .isInstanceOf(SubscriptionConflictException.class)
                .hasMessageContaining("Product");
    }

    @Test
    void detectsExactPartialContainingAndOpenEndedOverlaps() {
        assertOverlap(Period.existing(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                Period.candidate(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertOverlap(Period.existing(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                Period.candidate(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 10)));
        assertOverlap(Period.existing(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 20)),
                Period.candidate(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertOverlap(Period.existing(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                Period.candidate(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 20)));
        assertOverlap(Period.existing(LocalDate.of(2026, 1, 1), null),
                Period.candidate(LocalDate.of(2027, 1, 1), null));
        assertOverlap(Period.existing(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                Period.candidate(LocalDate.of(2026, 1, 31), null));
    }

    @Test
    void permitsAdjacentAndOneDayNonOverlappingPeriods() {
        Customer customer = createCustomer("9800000004");
        Product product = createProduct("Adjacent Milk");
        createSubscription(customer, product, "10", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        Subscription february = subscriptionService.createSubscription(createRequest(
                customer.getId(), product.getId(), "15", LocalDate.of(2026, 2, 1), null));

        assertThat(february.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

        Customer otherCustomer = createCustomer("9800000005");
        Product otherProduct = createProduct("One Day Milk");
        createSubscription(otherCustomer, otherProduct, "1",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1));
        Subscription nextDay = subscriptionService.createSubscription(createRequest(
                otherCustomer.getId(), otherProduct.getId(), "2",
                LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 2)));
        assertThat(nextDay.getEndDate()).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void pausedAndCancelledPeriodsDoNotBlockAnActivePeriod() {
        Customer customer = createCustomer("9800000006");
        Product product = createProduct("Historical Milk");
        Subscription paused = createSubscription(customer, product, "10",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        subscriptionService.updateSubscription(paused.getId(), updateRequest(
                "10", paused.getStartDate(), paused.getEndDate(), SubscriptionStatus.PAUSED));

        Subscription active = subscriptionService.createSubscription(createRequest(
                customer.getId(), product.getId(), "15",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertThat(active.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

        Customer cancelledCustomer = createCustomer("9800000014");
        Product cancelledProduct = createProduct("Cancelled Historical Milk");
        Subscription cancelled = createSubscription(cancelledCustomer, cancelledProduct, "10",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        subscriptionService.updateSubscription(cancelled.getId(), updateRequest(
                "10", cancelled.getStartDate(), cancelled.getEndDate(), SubscriptionStatus.CANCELLED));
        Subscription replacement = subscriptionService.createSubscription(createRequest(
                cancelledCustomer.getId(), cancelledProduct.getId(), "15",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertThat(replacement.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void resumingPausedSubscriptionChecksOverlapAndEligibility() {
        Customer customer = createCustomer("9800000007");
        Product product = createProduct("Resume Milk");
        Subscription paused = createSubscription(customer, product, "10",
                LocalDate.of(2026, 1, 1), null);
        subscriptionService.updateSubscription(paused.getId(), updateRequest(
                "10", paused.getStartDate(), null, SubscriptionStatus.PAUSED));
        createSubscription(customer, product, "12", LocalDate.of(2026, 1, 1), null);

        assertThatThrownBy(() -> subscriptionService.updateSubscription(paused.getId(), updateRequest(
                "10", paused.getStartDate(), null, SubscriptionStatus.ACTIVE)))
                .isInstanceOf(OverlappingSubscriptionException.class);
    }

    @Test
    void updatingFuturePeriodChecksForNewOverlaps() {
        Customer customer = createCustomer("9800000015");
        Product product = createProduct("Future Update Overlap");
        LocalDate today = LocalDate.now();
        Subscription first = createSubscription(customer, product, "10",
                today.plusDays(20), today.plusDays(40));
        Subscription second = createSubscription(customer, product, "12",
                today.plusDays(41), null);

        assertThatThrownBy(() -> subscriptionService.updateSubscription(second.getId(), updateRequest(
                "12", today.plusDays(40), null, SubscriptionStatus.ACTIVE)))
                .isInstanceOf(OverlappingSubscriptionException.class);
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void getsAndFiltersSubscriptionsByAllSupportedCriteria() {
        Customer customer = createCustomer("9800000008");
        Product product = createProduct("Filter Milk");
        Subscription active = createSubscription(customer, product, "10",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        Subscription future = createSubscription(customer, productService.createProduct(
                        new OwnerCreateProductRequest("Filter Ghee", null, ProductUnit.KILOGRAM,
                                new BigDecimal("500"))),
                "2", LocalDate.of(2026, 2, 1), null);

        assertThat(subscriptionService.getSubscriptionById(active.getId()).getId()).isEqualTo(active.getId());
        assertThat(subscriptionService.listSubscriptions(customer.getId(), null, null, null))
                .extracting(Subscription::getId).containsExactlyInAnyOrder(active.getId(), future.getId());
        assertThat(subscriptionService.listSubscriptions(null, product.getId(), null, null))
                .extracting(Subscription::getId).containsExactly(active.getId());
        assertThat(subscriptionService.listSubscriptions(null, null, SubscriptionStatus.ACTIVE, null))
                .extracting(Subscription::getId).contains(active.getId(), future.getId());
        assertThat(subscriptionService.listSubscriptions(null, null, null, LocalDate.of(2026, 1, 31)))
                .extracting(Subscription::getId).containsExactly(active.getId());
        assertThat(subscriptionService.listSubscriptions(customer.getId(), product.getId(),
                SubscriptionStatus.ACTIVE, LocalDate.of(2026, 1, 31)))
                .extracting(Subscription::getId).containsExactly(active.getId());
        assertThatThrownBy(() -> subscriptionService.getSubscriptionById(UUID.randomUUID()))
                .isInstanceOf(SubscriptionNotFoundException.class);
    }

    @Test
    void updatesFutureSubscriptionAndPreservesIdentityAndRelationships() {
        Customer customer = createCustomer("9800000009");
        Product product = createProduct("Update Milk");
        LocalDate originalStart = LocalDate.now().plusDays(20);
        Subscription subscription = createSubscription(customer, product, "10", originalStart, null);
        UUID id = subscription.getId();
        var createdAt = subscription.getCreatedAt();
        LocalDate updatedStart = originalStart.plusDays(1);
        LocalDate updatedEnd = updatedStart.plusDays(60);

        Subscription updated = subscriptionService.updateSubscription(id, updateRequest(
                "12.345", updatedStart, updatedEnd, SubscriptionStatus.PAUSED));

        assertThat(updated.getId()).isEqualTo(id);
        assertThat(updated.getCustomer().getId()).isEqualTo(customer.getId());
        assertThat(updated.getProduct().getId()).isEqualTo(product.getId());
        assertThat(updated.getDefaultQuantity()).isEqualByComparingTo("12.345");
        assertThat(updated.getStartDate()).isEqualTo(updatedStart);
        assertThat(updated.getEndDate()).isEqualTo(updatedEnd);
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void rejectsQuantityAndStartDateChangesAfterSubscriptionStarts() {
        Customer customer = createCustomer("9800000010");
        Product product = createProduct("Started Milk");
        LocalDate start = LocalDate.now().minusDays(1);
        Subscription subscription = createSubscription(customer, product, "10", start, null);
        entityManager.flush();
        entityManager.clear();

        Subscription unchangedQuantity = subscriptionService.updateSubscription(subscription.getId(),
                updateRequest("10", start, null, SubscriptionStatus.ACTIVE));
        assertThat(unchangedQuantity.getDefaultQuantity()).isEqualByComparingTo("10.000");

        assertThatThrownBy(() -> subscriptionService.updateSubscription(subscription.getId(),
                updateRequest("11", start, null, SubscriptionStatus.ACTIVE)))
                .isInstanceOf(SubscriptionConflictException.class)
                .hasMessageContaining("Quantity");
        assertThatThrownBy(() -> subscriptionService.updateSubscription(subscription.getId(),
                updateRequest("10", start.minusDays(1), null, SubscriptionStatus.ACTIVE)))
                .isInstanceOf(SubscriptionConflictException.class)
                .hasMessageContaining("Start date");
    }

    @Test
    void allowsStartedSubscriptionToShortenEndDatePauseAndCancel() {
        Customer customer = createCustomer("9800000011");
        Product product = createProduct("Close Milk");
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(1);
        LocalDate end = today.plusDays(30);
        Subscription subscription = createSubscription(customer, product, "10", start, end);

        Subscription shortened = subscriptionService.updateSubscription(subscription.getId(),
                updateRequest("10", start, today.plusDays(10), SubscriptionStatus.PAUSED));
        assertThat(shortened.getEndDate()).isEqualTo(today.plusDays(10));
        assertThat(shortened.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);

        Subscription cancelled = subscriptionService.updateSubscription(subscription.getId(),
                updateRequest("10", start, shortened.getEndDate(), SubscriptionStatus.CANCELLED));
        assertThat(cancelled.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }

    @Test
    void retainsOldPeriodWhenAReplacementPlanIsCreated() {
        Customer customer = createCustomer("9800000012");
        Product product = createProduct("Retained Milk");
        Subscription old = createSubscription(customer, product, "10",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        Subscription replacement = subscriptionService.createSubscription(createRequest(
                customer.getId(), product.getId(), "15", LocalDate.of(2026, 2, 1), null));

        assertThat(subscriptionService.getSubscriptionById(old.getId()).getDefaultQuantity())
                .isEqualByComparingTo("10.000");
        assertThat(subscriptionService.getSubscriptionById(old.getId()).getEndDate())
                .isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(subscriptionService.getSubscriptionById(replacement.getId()).getDefaultQuantity())
                .isEqualByComparingTo("15.000");
    }

    @Test
    void requiresActiveEntitiesAgainWhenResuming() {
        Customer customer = createCustomer("9800000013");
        Product product = createProduct("Eligibility Resume");
        Subscription subscription = createSubscription(customer, product, "1",
                LocalDate.now().plusDays(10), null);
        subscriptionService.updateSubscription(subscription.getId(), updateRequest(
                "1", subscription.getStartDate(), null, SubscriptionStatus.PAUSED));
        productService.updateProduct(product.getId(), new OwnerUpdateProductRequest(
                product.getName(), product.getDescription(), product.getUnit(),
                product.getPrice(), ProductStatus.INACTIVE));

        assertThatThrownBy(() -> subscriptionService.updateSubscription(subscription.getId(), updateRequest(
                "1", subscription.getStartDate(), null, SubscriptionStatus.ACTIVE)))
                .isInstanceOf(SubscriptionConflictException.class)
                .hasMessageContaining("Product");
    }

    private void assertOverlap(Period existing, Period candidate) {
        Customer customer = createCustomer(phone());
        Product product = createProduct("Overlap " + UUID.randomUUID());
        createSubscription(customer, product, "1", existing.start(), existing.end());
        assertThatThrownBy(() -> subscriptionService.createSubscription(createRequest(
                customer.getId(), product.getId(), "2", candidate.start(), candidate.end())))
                .isInstanceOf(OverlappingSubscriptionException.class);
    }

    private Customer createCustomer(String phone) {
        return customerService.createCustomer(new OwnerCreateCustomerRequest(
                phone, "secret123", "Test Customer", null, BillingType.MONTHLY,
                CustomerStatus.ACTIVE, LocalDate.now()));
    }

    private Product createProduct(String name) {
        return productService.createProduct(new OwnerCreateProductRequest(
                name, null, ProductUnit.LITRE, new BigDecimal("60.00")));
    }

    private Subscription createSubscription(
            Customer customer, Product product, String quantity, LocalDate start, LocalDate end
    ) {
        return subscriptionService.createSubscription(
                createRequest(customer.getId(), product.getId(), quantity, start, end));
    }

    private OwnerCreateSubscriptionRequest createRequest(
            UUID customerId, UUID productId, String quantity, LocalDate start, LocalDate end
    ) {
        return new OwnerCreateSubscriptionRequest(
                customerId, productId, new BigDecimal(quantity), start, end);
    }

    private OwnerUpdateSubscriptionRequest updateRequest(
            String quantity, LocalDate start, LocalDate end, SubscriptionStatus status
    ) {
        return new OwnerUpdateSubscriptionRequest(new BigDecimal(quantity), start, end, status);
    }

    private String phone() {
        return "98" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    private record Period(LocalDate start, LocalDate end) {
        static Period existing(LocalDate start, LocalDate end) {
            return new Period(start, end);
        }

        static Period candidate(LocalDate start, LocalDate end) {
            return new Period(start, end);
        }
    }
}
