package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "bills",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bills_period_customer",
                columnNames = {"billing_period_id", "customer_id"}),
        indexes = @Index(name = "idx_bills_customer_period", columnList = "customer_id, period_start")
)
@Check(
        name = "ck_bills_status_issue_metadata",
        constraints = """
                (status = 'DRAFT' AND issued_at IS NULL AND issued_by IS NULL)
                OR
                (status = 'ISSUED' AND issued_at IS NOT NULL AND issued_by IS NOT NULL)
                """
)
@Check(name = "ck_bills_total_positive", constraints = "total_amount > 0")
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billing_period_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_bills_billing_period"))
    private BillingPeriod billingPeriod;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_bills_customer"))
    private Customer customer;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Column(name = "customer_name_snapshot", nullable = false, length = 100, updatable = false)
    private String customerNameSnapshot;

    @Column(name = "customer_phone_snapshot", length = 15, updatable = false)
    private String customerPhoneSnapshot;

    @Column(name = "customer_address_snapshot", length = 500, updatable = false)
    private String customerAddressSnapshot;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BillStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by", foreignKey = @ForeignKey(name = "fk_bills_issued_by"))
    private User issuedBy;

    @OneToMany(mappedBy = "bill", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    @OrderBy("recordDate ASC, slot ASC, id ASC")
    private List<BillLine> lines = new ArrayList<>();

    protected Bill() {
    }

    Bill(
            BillingPeriod billingPeriod,
            Customer customer,
            String customerNameSnapshot,
            String customerPhoneSnapshot,
            String customerAddressSnapshot,
            BigDecimal totalAmount
    ) {
        this.billingPeriod = billingPeriod;
        this.customer = customer;
        this.periodStart = billingPeriod.getPeriodStart();
        this.periodEnd = billingPeriod.getPeriodEnd();
        this.customerNameSnapshot = customerNameSnapshot;
        this.customerPhoneSnapshot = customerPhoneSnapshot;
        this.customerAddressSnapshot = customerAddressSnapshot;
        this.totalAmount = totalAmount;
        this.status = BillStatus.DRAFT;
    }

    void addLine(BillLine line) {
        lines.add(line);
        line.setBill(this);
    }

    void issue(User owner, Instant now) {
        if (status == BillStatus.DRAFT) {
            status = BillStatus.ISSUED;
            issuedBy = owner;
            issuedAt = now;
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public BillingPeriod getBillingPeriod() {
        return billingPeriod;
    }

    public Customer getCustomer() {
        return customer;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public String getCustomerNameSnapshot() {
        return customerNameSnapshot;
    }

    public String getCustomerPhoneSnapshot() {
        return customerPhoneSnapshot;
    }

    public String getCustomerAddressSnapshot() {
        return customerAddressSnapshot;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BillStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public User getIssuedBy() {
        return issuedBy;
    }

    public List<BillLine> getLines() {
        return List.copyOf(lines);
    }
}
