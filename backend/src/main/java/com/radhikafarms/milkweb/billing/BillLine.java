package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.dailyrecord.DailyRecord;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "bill_lines",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bill_lines_daily_record",
                columnNames = "daily_record_id"),
        indexes = @Index(name = "idx_bill_lines_bill", columnList = "bill_id")
)
@Check(name = "ck_bill_lines_values", constraints = "quantity > 0 AND unit_price >= 0 AND amount >= 0")
public class BillLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_bill_lines_bill"))
    private Bill bill;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_record_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_bill_lines_daily_record"))
    private DailyRecord dailyRecord;

    @Column(name = "record_date", nullable = false, updatable = false)
    private LocalDate recordDate;

    @Column(nullable = false, length = 20, updatable = false)
    private String slot;

    @Column(nullable = false, length = 150, updatable = false)
    private String description;

    @Column(nullable = false, precision = 8, scale = 3, updatable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    protected BillLine() {
    }

    BillLine(
            DailyRecord dailyRecord,
            String description
    ) {
        this.dailyRecord = dailyRecord;
        this.recordDate = dailyRecord.getRecordDate();
        this.slot = dailyRecord.getSlot().name();
        this.description = description;
        this.quantity = dailyRecord.getQuantity();
        this.unitPrice = dailyRecord.getUnitPriceSnapshot();
        this.amount = dailyRecord.getAmount();
    }

    void setBill(Bill bill) {
        this.bill = bill;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDailyRecordId() {
        return dailyRecord.getId();
    }

    public LocalDate getRecordDate() {
        return recordDate;
    }

    public String getSlot() {
        return slot;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
