package com.radhikafarms.milkweb.dailyrecord;

import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.customer.CustomerRepository;
import com.radhikafarms.milkweb.customer.CustomerStatus;
import com.radhikafarms.milkweb.customer.exception.CustomerNotFoundException;
import com.radhikafarms.milkweb.dailyrecord.exception.DailyRecordConflictException;
import com.radhikafarms.milkweb.dailyrecord.exception.DailyRecordNotFoundException;
import com.radhikafarms.milkweb.dailyrecord.exception.DuplicateDailyRecordException;
import com.radhikafarms.milkweb.product.Product;
import com.radhikafarms.milkweb.product.ProductRepository;
import com.radhikafarms.milkweb.product.ProductStatus;
import com.radhikafarms.milkweb.product.exception.ProductNotFoundException;
import com.radhikafarms.milkweb.subscription.Subscription;
import com.radhikafarms.milkweb.subscription.SubscriptionRepository;
import com.radhikafarms.milkweb.subscription.exception.SubscriptionNotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DailyRecordService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final DailyRecordRepository dailyRecordRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final SubscriptionRepository subscriptionRepository;

    public DailyRecordService(
            DailyRecordRepository dailyRecordRepository,
            CustomerRepository customerRepository,
            ProductRepository productRepository,
            SubscriptionRepository subscriptionRepository
    ) {
        this.dailyRecordRepository = dailyRecordRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Transactional
    public DailyRecord createDailyRecord(OwnerCreateDailyRecordRequest request) {
        validateCreateValues(request);
        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found: " + request.customerId()));
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));
        Subscription subscription = request.subscriptionId() == null
                ? null
                : subscriptionRepository.findById(request.subscriptionId())
                        .orElseThrow(() -> new SubscriptionNotFoundException(request.subscriptionId()));

        validateSubscription(subscription, customer, product, request.recordDate());
        validateSupplyEligibility(customer, product, request.status());
        if (dailyRecordRepository.existsByCustomer_IdAndProduct_IdAndRecordDateAndSlot(
                customer.getId(), product.getId(), request.recordDate(), request.slot())) {
            throw new DuplicateDailyRecordException();
        }

        DailyRecord record = new DailyRecord();
        record.setCustomer(customer);
        record.setProduct(product);
        record.setSubscription(subscription);
        record.setRecordDate(request.recordDate());
        record.setSlot(request.slot());
        record.setQuantity(request.quantity());
        record.setStatus(request.status());
        if (isChargeable(request.status())) {
            record.setUnitPriceSnapshot(request.unitPriceSnapshot());
            record.setAmount(calculateAmount(request.quantity(), request.unitPriceSnapshot()));
        } else {
            record.setUnitPriceSnapshot(null);
            record.setAmount(ZERO);
        }
        return saveAndTranslateDuplicate(record);
    }

    public DailyRecord getDailyRecordById(UUID dailyRecordId) {
        return dailyRecordRepository.findById(dailyRecordId)
                .orElseThrow(() -> new DailyRecordNotFoundException(dailyRecordId));
    }

    public Page<DailyRecord> listDailyRecords(
            UUID customerId,
            UUID productId,
            LocalDate recordDate,
            LocalDate fromDate,
            LocalDate toDate,
            DailyRecordSlot slot,
            DailyRecordStatus status,
            Pageable pageable
    ) {
        return dailyRecordRepository.findFiltered(
                customerId, productId, recordDate, fromDate, toDate, slot, status, pageable);
    }

    @Transactional
    public DailyRecord updateDailyRecord(UUID dailyRecordId, OwnerUpdateDailyRecordRequest request) {
        DailyRecord record = dailyRecordRepository.findById(dailyRecordId)
                .orElseThrow(() -> new DailyRecordNotFoundException(dailyRecordId));
        if (record.getRecordDate().isBefore(LocalDate.now())) {
            throw new DailyRecordConflictException("Past daily records cannot be updated");
        }
        validateUpdateValues(request);

        boolean wasChargeable = isChargeable(record.getStatus());
        boolean willBeChargeable = isChargeable(request.status());
        BigDecimal priceSnapshot = null;

        if (willBeChargeable) {
            validateSupplyEligibility(record.getCustomer(), record.getProduct(), request.status());
            if (wasChargeable) {
                BigDecimal existingPrice = record.getUnitPriceSnapshot();
                if (request.unitPriceSnapshot() != null
                        && request.unitPriceSnapshot().compareTo(existingPrice) != 0) {
                    throw new DailyRecordConflictException(
                            "The unit price snapshot cannot be changed once it has been set");
                }
                priceSnapshot = existingPrice;
            } else {
                if (request.unitPriceSnapshot() == null) {
                    throw new DailyRecordConflictException(
                            "A unit price snapshot is required when changing to a supplied status");
                }
                priceSnapshot = request.unitPriceSnapshot();
            }
        } else if (request.unitPriceSnapshot() != null) {
            throw new DailyRecordConflictException(
                    "A unit price snapshot is not allowed for a no-supply status");
        }

        if (willBeChargeable && request.quantity().signum() <= 0) {
            throw new DailyRecordConflictException("Quantity must be greater than zero for supplied records");
        }
        if (!willBeChargeable && request.quantity().signum() != 0) {
            throw new DailyRecordConflictException("Quantity must be zero for no-supply records");
        }

        record.setStatus(request.status());
        record.setQuantity(request.quantity());
        record.setUnitPriceSnapshot(priceSnapshot);
        record.setAmount(willBeChargeable
                ? calculateAmount(request.quantity(), priceSnapshot)
                : ZERO);
        return dailyRecordRepository.saveAndFlush(record);
    }

    private void validateCreateValues(OwnerCreateDailyRecordRequest request) {
        if (request.customerId() == null || request.productId() == null
                || request.recordDate() == null || request.slot() == null
                || request.quantity() == null || request.status() == null) {
            throw new DailyRecordConflictException("Required DailyRecord fields are missing");
        }
        validateQuantityAndPrice(request.quantity(), request.unitPriceSnapshot(), request.status());
        if (request.recordDate().isAfter(LocalDate.now())) {
            throw new DailyRecordConflictException("Daily records cannot be created for a future date");
        }
    }

    private void validateUpdateValues(OwnerUpdateDailyRecordRequest request) {
        if (request.quantity() == null || request.status() == null) {
            throw new DailyRecordConflictException("Quantity and status are required");
        }
        if (request.unitPriceSnapshot() != null && request.unitPriceSnapshot().signum() < 0) {
            throw new DailyRecordConflictException("Unit price snapshot cannot be negative");
        }
        validateQuantity(request.quantity());
        if (request.unitPriceSnapshot() != null) {
            validateUnitPrice(request.unitPriceSnapshot());
        }
    }

    private void validateQuantityAndPrice(
            BigDecimal quantity,
            BigDecimal unitPriceSnapshot,
            DailyRecordStatus status
    ) {
        validateQuantity(quantity);
        if (isChargeable(status)) {
            if (quantity.signum() <= 0) {
                throw new DailyRecordConflictException("Quantity must be greater than zero for supplied records");
            }
            if (unitPriceSnapshot == null) {
                throw new DailyRecordConflictException("A unit price snapshot is required for supplied records");
            }
            validateUnitPrice(unitPriceSnapshot);
        } else {
            if (quantity.signum() != 0) {
                throw new DailyRecordConflictException("Quantity must be zero for no-supply records");
            }
            if (unitPriceSnapshot != null) {
                throw new DailyRecordConflictException("A unit price snapshot is not allowed for no-supply records");
            }
        }
    }

    private void validateUnitPrice(BigDecimal unitPriceSnapshot) {
        if (unitPriceSnapshot.signum() < 0) {
            throw new DailyRecordConflictException("Unit price snapshot cannot be negative");
        }
        if (unitPriceSnapshot.scale() > 2 || unitPriceSnapshot.precision() - unitPriceSnapshot.scale() > 10) {
            throw new DailyRecordConflictException("Unit price snapshot exceeds NUMERIC(12,2) precision");
        }
    }

    private void validateQuantity(BigDecimal quantity) {
        if (quantity.scale() > 3 || quantity.precision() - quantity.scale() > 5) {
            throw new DailyRecordConflictException("Quantity exceeds NUMERIC(8,3) precision");
        }
    }

    private void validateSubscription(
            Subscription subscription,
            Customer customer,
            Product product,
            LocalDate recordDate
    ) {
        if (subscription == null) {
            return;
        }
        if (!subscription.getCustomer().getId().equals(customer.getId())) {
            throw new DailyRecordConflictException("Subscription belongs to a different customer");
        }
        if (!subscription.getProduct().getId().equals(product.getId())) {
            throw new DailyRecordConflictException("Subscription is for a different product");
        }
        if (recordDate.isBefore(subscription.getStartDate())
                || (subscription.getEndDate() != null && recordDate.isAfter(subscription.getEndDate()))) {
            throw new DailyRecordConflictException("Record date is outside the subscription date range");
        }
    }

    private void validateSupplyEligibility(
            Customer customer, Product product, DailyRecordStatus status
    ) {
        if (!isChargeable(status)) {
            return;
        }
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new DailyRecordConflictException("Customer must be ACTIVE for positive-quantity supply");
        }
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new DailyRecordConflictException("Product must be ACTIVE for positive-quantity supply");
        }
    }

    private boolean isChargeable(DailyRecordStatus status) {
        return status == DailyRecordStatus.COLLECTED || status == DailyRecordStatus.EXTRA;
    }

    private BigDecimal calculateAmount(BigDecimal quantity, BigDecimal unitPriceSnapshot) {
        return quantity.multiply(unitPriceSnapshot).setScale(2, RoundingMode.HALF_UP);
    }

    private DailyRecord saveAndTranslateDuplicate(DailyRecord record) {
        try {
            return dailyRecordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            if (isDailyRecordUniqueConstraintViolation(ex)) {
                throw new DuplicateDailyRecordException();
            }
            throw ex;
        }
    }

    private boolean isDailyRecordUniqueConstraintViolation(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && "uk_daily_records_customer_product_date_slot"
                    .equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
