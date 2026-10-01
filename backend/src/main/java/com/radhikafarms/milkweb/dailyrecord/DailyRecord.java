package com.radhikafarms.milkweb.dailyrecord;

import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.product.Product;
import com.radhikafarms.milkweb.subscription.Subscription;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "daily_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_daily_records_customer_product_date_slot",
                columnNames = {"customer_id", "product_id", "record_date", "slot"}),
        indexes = {
                @Index(name = "idx_daily_records_customer_date",
                        columnList = "customer_id, record_date"),
                @Index(name = "idx_daily_records_product_date",
                        columnList = "product_id, record_date")
        }
)
@Check(name = "ck_daily_records_quantity_nonnegative", constraints = "quantity >= 0")
@Check(name = "ck_daily_records_amount_nonnegative", constraints = "amount >= 0")
@Check(name = "ck_daily_records_price_nonnegative",
        constraints = "unit_price_snapshot IS NULL OR unit_price_snapshot >= 0")
@Check(name = "ck_daily_records_status_values",
        constraints = "status IN ('COLLECTED', 'SKIPPED', 'PAUSED', 'EXTRA')")
@Check(name = "ck_daily_records_slot_values", constraints = "slot IN ('MORNING', 'EVENING')")
@Check(
        name = "ck_daily_records_status_values_consistent",
        constraints = """
                (status IN ('COLLECTED', 'EXTRA') AND quantity > 0 AND unit_price_snapshot IS NOT NULL)
                OR
                (status IN ('SKIPPED', 'PAUSED') AND quantity = 0
                    AND unit_price_snapshot IS NULL AND amount = 0)
                """
)
public class DailyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_daily_records_customer"))
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_daily_records_product"))
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id",
            foreignKey = @ForeignKey(name = "fk_daily_records_subscription"))
    private Subscription subscription;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DailyRecordSlot slot;

    @Column(nullable = false, precision = 8, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price_snapshot", precision = 12, scale = 2)
    private BigDecimal unitPriceSnapshot;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DailyRecordStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DailyRecord() {
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public Subscription getSubscription() {
        return subscription;
    }

    public void setSubscription(Subscription subscription) {
        this.subscription = subscription;
    }

    public LocalDate getRecordDate() {
        return recordDate;
    }

    public void setRecordDate(LocalDate recordDate) {
        this.recordDate = recordDate;
    }

    public DailyRecordSlot getSlot() {
        return slot;
    }

    public void setSlot(DailyRecordSlot slot) {
        this.slot = slot;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPriceSnapshot() {
        return unitPriceSnapshot;
    }

    public void setUnitPriceSnapshot(BigDecimal unitPriceSnapshot) {
        this.unitPriceSnapshot = unitPriceSnapshot;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public DailyRecordStatus getStatus() {
        return status;
    }

    public void setStatus(DailyRecordStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
