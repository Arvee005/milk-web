package com.radhikafarms.milkweb.ledger;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An owner credit or extra charge. With a bill it changes what that bill asks for; without a bill it is a
 * credit held for future bills. Account-level charges are not supported.
 */
@Entity
@Table(name = "account_adjustments",
        uniqueConstraints = @UniqueConstraint(name = "uk_account_adjustments_idempotency_key", columnNames = "idempotency_key"),
        indexes = {
                @Index(name = "idx_account_adjustments_customer", columnList = "customer_id"),
                @Index(name = "idx_account_adjustments_bill", columnList = "bill_id")
        })
@Check(name = "ck_account_adjustments_scope", constraints = "amount > 0 AND (bill_id IS NOT NULL OR direction = 'CREDIT')")
public class AccountAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "bill_id", updatable = false)
    private UUID billId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private AdjustmentDirection direction;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
    private String idempotencyKey;

    protected AccountAdjustment() {
    }

    AccountAdjustment(UUID customerId, UUID billId, AdjustmentDirection direction, BigDecimal amount, String reason,
                      Instant recordedAt, UUID recordedBy, String idempotencyKey) {
        this.customerId = customerId;
        this.billId = billId;
        this.direction = direction;
        this.amount = amount;
        this.currency = Ledger.CURRENCY;
        this.reason = reason;
        this.recordedAt = recordedAt;
        this.recordedBy = recordedBy;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getBillId() { return billId; }
    public AdjustmentDirection getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public Instant getRecordedAt() { return recordedAt; }
    public UUID getRecordedBy() { return recordedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
