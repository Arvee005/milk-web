package com.radhikafarms.milkweb.ledger;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Marks a payment as entered in error. The payment row itself is never changed or deleted. */
@Entity
@Table(name = "payment_reversals",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_reversals_payment", columnNames = "payment_id"))
public class PaymentReversal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    protected PaymentReversal() {
    }

    PaymentReversal(UUID paymentId, String reason, Instant recordedAt, UUID recordedBy) {
        this.paymentId = paymentId;
        this.reason = reason;
        this.recordedAt = recordedAt;
        this.recordedBy = recordedBy;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public String getReason() { return reason; }
    public Instant getRecordedAt() { return recordedAt; }
    public UUID getRecordedBy() { return recordedBy; }
}
