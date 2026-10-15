package com.radhikafarms.milkweb.ledger;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Credit applied to an issued bill (ALLOCATE) or taken back after a payment reversal (RELEASE). */
@Entity
@Table(name = "bill_allocations",
        indexes = {
                @Index(name = "idx_bill_allocations_customer", columnList = "customer_id"),
                @Index(name = "idx_bill_allocations_bill", columnList = "bill_id")
        })
@Check(name = "ck_bill_allocations_amount_positive", constraints = "amount > 0")
public class BillAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "bill_id", nullable = false, updatable = false)
    private UUID billId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private AllocationKind kind;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20, updatable = false)
    private AllocationTrigger triggerType;

    @Column(name = "trigger_id", nullable = false, updatable = false)
    private UUID triggerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BillAllocation() {
    }

    BillAllocation(UUID customerId, UUID billId, AllocationKind kind, BigDecimal amount,
                   AllocationTrigger triggerType, UUID triggerId, Instant createdAt) {
        this.customerId = customerId;
        this.billId = billId;
        this.kind = kind;
        this.amount = amount;
        this.triggerType = triggerType;
        this.triggerId = triggerId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getBillId() { return billId; }
    public AllocationKind getKind() { return kind; }
    public BigDecimal getAmount() { return amount; }
    public AllocationTrigger getTriggerType() { return triggerType; }
    public UUID getTriggerId() { return triggerId; }
    public Instant getCreatedAt() { return createdAt; }

    /** The signed effect on how much of the bill is paid. */
    BigDecimal signedAmount() {
        return kind == AllocationKind.ALLOCATE ? amount : amount.negate();
    }
}
