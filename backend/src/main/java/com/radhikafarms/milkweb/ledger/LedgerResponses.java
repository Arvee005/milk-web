package com.radhikafarms.milkweb.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response types for the ledger API. Amounts are INR with two decimals; none are stored balances. */
public final class LedgerResponses {

    private LedgerResponses() {
    }

    public enum BillPaymentStatus { PAID, PART_PAID, UNPAID }

    /** What a customer owes now, and the credit held for future bills. Both are never negative. */
    public record AccountTotals(BigDecimal amountDue, BigDecimal credit) {
    }

    public record AppliedAmount(UUID billId, String period, BigDecimal amount) {
    }

    public record PaymentResponse(
            UUID id, UUID customerId, BigDecimal amount, String currency, PaymentMethod method, String reference,
            String note, LocalDate receivedOn, Instant recordedAt, boolean reversed,
            List<AppliedAmount> appliedTo, BigDecimal keptAsCredit, AccountTotals account) {
    }

    public record ReversalResponse(
            UUID id, UUID paymentId, String reason, Instant recordedAt, List<AppliedAmount> takenBack,
            AccountTotals account) {
    }

    public record AdjustmentEntry(
            UUID id, UUID billId, String billPeriod, AdjustmentDirection direction, BigDecimal amount, String reason,
            Instant recordedAt) {
    }

    public record AdjustmentResponse(List<AdjustmentEntry> adjustments, List<AppliedAmount> appliedTo,
                                     AccountTotals account) {
    }

    public record RefundResponse(
            UUID id, BigDecimal amount, PaymentMethod method, String reason, LocalDate refundedOn, Instant recordedAt,
            AccountTotals account) {
    }

    /**
     * One line in a bill's payment history. {@code amount} is the effect on what is paid: positive for money or
     * credit applied, negative when a reversed payment is taken back. Adjustment lines show the change to the
     * amount asked for instead.
     */
    public record BillLedgerEntry(String type, LocalDate date, PaymentMethod method, BigDecimal amount, String reason) {
    }

    public record BillBalanceResponse(
            UUID billId, UUID customerId, String customerName, String period, LocalDate periodStart,
            Instant issuedAt, BigDecimal billTotal, BigDecimal charges, BigDecimal credits, BigDecimal amountToPay,
            BigDecimal paid, BigDecimal due, BillPaymentStatus status, List<BillLedgerEntry> entries) {
    }

    public record AccountResponse(
            UUID customerId, String customerName, String currency, BigDecimal amountDue, BigDecimal credit,
            BigDecimal totalBilled, BigDecimal totalReceived, List<BillBalanceResponse> bills) {
    }

    public record CustomerAccountSummary(UUID customerId, String name, String phone, BigDecimal amountDue,
                                         BigDecimal credit) {
    }

    /**
     * One entry in a customer's money history. Types: PAYMENT, PAYMENT_REVERSAL, REFUND, CREDIT, CHARGE.
     * {@code note} is owner-only and always null in customer responses.
     */
    public record TransactionResponse(
            UUID id, String type, LocalDate date, Instant recordedAt, BigDecimal amount, PaymentMethod method,
            String reference, String reason, String note, UUID billId, String billPeriod, UUID paymentId,
            boolean reversed) {
    }
}
