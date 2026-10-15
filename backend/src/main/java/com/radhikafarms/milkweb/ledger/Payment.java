package com.radhikafarms.milkweb.ledger;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Money actually received from a customer. Never updated; corrected with a {@link PaymentReversal}. */
@Entity
@Table(name = "payments",
        uniqueConstraints = @UniqueConstraint(name = "uk_payments_idempotency_key", columnNames = "idempotency_key"),
        indexes = @Index(name = "idx_payments_customer_received", columnList = "customer_id, received_on"))
@Check(name = "ck_payments_amount_positive", constraints = "amount > 0")
public class Payment {

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

    @Column(length = 64, updatable = false)
    private String reference;

    @Column(length = 255, updatable = false)
    private String note;

    @Column(name = "received_on", nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "idempotency_key", nullable = false, length = 80, updatable = false)
    private String idempotencyKey;

    protected Payment() {
    }

    Payment(UUID customerId, BigDecimal amount, PaymentMethod method, String reference, String note,
            LocalDate receivedOn, Instant recordedAt, UUID recordedBy, String idempotencyKey) {
        this.customerId = customerId;
        this.amount = amount;
        this.currency = Ledger.CURRENCY;
        this.method = method;
        this.reference = reference;
        this.note = note;
        this.receivedOn = receivedOn;
        this.recordedAt = recordedAt;
        this.recordedBy = recordedBy;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentMethod getMethod() { return method; }
    public String getReference() { return reference; }
    public String getNote() { return note; }
    public LocalDate getReceivedOn() { return receivedOn; }
    public Instant getRecordedAt() { return recordedAt; }
    public UUID getRecordedBy() { return recordedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
