package com.radhikafarms.milkweb.subscription;

import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.customer.CustomerRepository;
import com.radhikafarms.milkweb.customer.CustomerStatus;
import com.radhikafarms.milkweb.customer.exception.CustomerNotFoundException;
import com.radhikafarms.milkweb.product.Product;
import com.radhikafarms.milkweb.product.ProductRepository;
import com.radhikafarms.milkweb.product.ProductStatus;
import com.radhikafarms.milkweb.product.exception.ProductNotFoundException;
import com.radhikafarms.milkweb.subscription.exception.OverlappingSubscriptionException;
import com.radhikafarms.milkweb.subscription.exception.SubscriptionConflictException;
import com.radhikafarms.milkweb.subscription.exception.SubscriptionNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;

    public SubscriptionService(
            SubscriptionRepository subscriptionRepository,
            CustomerRepository customerRepository,
            ProductRepository productRepository
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Subscription createSubscription(OwnerCreateSubscriptionRequest request) {
        validateDateRange(request.startDate(), request.endDate());
        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new CustomerNotFoundException(
                        "Customer not found: " + request.customerId()));
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));

        requireEligible(customer, product);
        requireNoOverlap(customer.getId(), product.getId(),
                request.startDate(), request.endDate(), null);

        Subscription subscription = new Subscription();
        subscription.setCustomer(customer);
        subscription.setProduct(product);
        subscription.setDefaultQuantity(request.defaultQuantity());
        subscription.setStartDate(request.startDate());
        subscription.setEndDate(request.endDate());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        return subscriptionRepository.save(subscription);
    }

    public Subscription getSubscriptionById(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));
    }

    public List<Subscription> listSubscriptions(
            UUID customerId,
            UUID productId,
            SubscriptionStatus status,
            LocalDate effectiveOn
    ) {
        return subscriptionRepository.findByFilters(customerId, productId, status, effectiveOn);
    }

    public List<Subscription> listCustomerSubscriptions(UUID userId) {
        Customer customer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomerNotFoundException(userId));
        return subscriptionRepository.findCustomerSubscriptions(customer.getId());
    }

    @Transactional
    public Subscription updateSubscription(UUID subscriptionId, OwnerUpdateSubscriptionRequest request) {
        validateDateRange(request.startDate(), request.endDate());
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));

        LocalDate today = LocalDate.now();
        boolean alreadyStarted = !subscription.getStartDate().isAfter(today);
        if (alreadyStarted) {
            if (subscription.getDefaultQuantity().compareTo(request.defaultQuantity()) != 0) {
                throw new SubscriptionConflictException(
                        "Quantity cannot be changed after the subscription has started");
            }
            if (!subscription.getStartDate().equals(request.startDate())) {
                throw new SubscriptionConflictException(
                        "Start date cannot be changed after the subscription has started");
            }
            validateStartedEndDateChange(subscription, request.endDate(), today);
        } else if (request.startDate() != null && !request.startDate().isAfter(today)
                && !subscription.getStartDate().equals(request.startDate())) {
            throw new SubscriptionConflictException(
                    "A future subscription cannot be moved to today or an earlier date");
        }

        if (subscription.getStatus() == SubscriptionStatus.CANCELLED
                && request.status() != SubscriptionStatus.CANCELLED) {
            throw new SubscriptionConflictException("A cancelled subscription cannot be reactivated");
        }

        if (request.status() == SubscriptionStatus.ACTIVE) {
            requireEligible(subscription.getCustomer(), subscription.getProduct());
            requireNoOverlap(
                    subscription.getCustomer().getId(),
                    subscription.getProduct().getId(),
                    request.startDate(),
                    request.endDate(),
                    subscriptionId
            );
        }

        subscription.setDefaultQuantity(request.defaultQuantity());
        subscription.setStartDate(request.startDate());
        subscription.setEndDate(request.endDate());
        subscription.setStatus(request.status());
        return subscription;
    }

    private void validateStartedEndDateChange(
            Subscription subscription,
            LocalDate requestedEndDate,
            LocalDate today
    ) {
        if (subscription.getEndDate() != null && subscription.getEndDate().isBefore(today)
                && !subscription.getEndDate().equals(requestedEndDate)) {
            throw new SubscriptionConflictException("An ended subscription's date range cannot be changed");
        }
        if (subscription.getEndDate() == null && requestedEndDate == null) {
            return;
        }
        if (subscription.getEndDate() != null && subscription.getEndDate().equals(requestedEndDate)) {
            return;
        }
        if (requestedEndDate == null || requestedEndDate.isBefore(today)
                || (subscription.getEndDate() != null
                && requestedEndDate.isAfter(subscription.getEndDate()))) {
            throw new SubscriptionConflictException(
                    "A started subscription end date may only be shortened to today or a future date");
        }
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new SubscriptionConflictException("endDate must be on or after startDate");
        }
    }

    private void requireEligible(Customer customer, Product product) {
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new SubscriptionConflictException("Customer must be ACTIVE");
        }
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new SubscriptionConflictException("Product must be ACTIVE");
        }
    }

    private void requireNoOverlap(
            UUID customerId,
            UUID productId,
            LocalDate startDate,
            LocalDate endDate,
            UUID excludedSubscriptionId
    ) {
        if (!subscriptionRepository.findOverlappingActiveSubscriptions(
                customerId, productId, startDate, endDate, excludedSubscriptionId).isEmpty()) {
            throw new OverlappingSubscriptionException();
        }
    }
}
