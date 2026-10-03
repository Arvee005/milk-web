package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "billing_periods",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_billing_periods_period_start",
                columnNames = "period_start")
)
@Check(
        name = "ck_billing_periods_status_metadata",
        constraints = """
                (status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL)
                OR
                (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL)
                """
)
public class BillingPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BillingPeriodStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by", foreignKey = @ForeignKey(name = "fk_billing_periods_closed_by"))
    private User closedBy;

    protected BillingPeriod() {
    }

    BillingPeriod(LocalDate periodStart, LocalDate periodEnd) {
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.status = BillingPeriodStatus.OPEN;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    void close(User owner, Instant now) {
        if (status == BillingPeriodStatus.CLOSED) {
            return;
        }
        status = BillingPeriodStatus.CLOSED;
        closedAt = now;
        closedBy = owner;
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public BillingPeriodStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public User getClosedBy() {
        return closedBy;
    }
}
