package com.radhikafarms.milkweb.ledger;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Money the farm gave back out of a customer's credit. Milk Web only records it; it sends no money. */
@Entity
@Table(name = "refunds",
        uniqueConstraints = @UniqueConstraint(name = "uk_refunds_idempotency_key", columnNames = "idempotency_key"),
        indexes = @Index(name = "idx_refunds_customer", columnList = "customer_id"))
@Check(name = "ck_refunds_amount_positive", constraints = "amount > 0")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PaymentMethod method;

    @Column(nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "refunded_on", nullable = false, updatable = false)
    private LocalDate refundedOn;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
    private String idempotencyKey;

    protected Refund() {
    }

    Refund(UUID customerId, BigDecimal amount, PaymentMethod method, String reason, LocalDate refundedOn,
           Instant recordedAt, UUID recordedBy, String idempotencyKey) {
        this.customerId = customerId;
        this.amount = amount;
        this.currency = Ledger.CURRENCY;
        this.method = method;
        this.reason = reason;
        this.refundedOn = refundedOn;
        this.recordedAt = recordedAt;
        this.recordedBy = recordedBy;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentMethod getMethod() { return method; }
    public String getReason() { return reason; }
    public LocalDate getRefundedOn() { return refundedOn; }
    public Instant getRecordedAt() { return recordedAt; }
    public UUID getRecordedBy() { return recordedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
