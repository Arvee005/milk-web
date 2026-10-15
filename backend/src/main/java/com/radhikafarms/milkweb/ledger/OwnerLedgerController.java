package com.radhikafarms.milkweb.ledger;

import com.radhikafarms.milkweb.auth.MilkWebUserDetails;
import com.radhikafarms.milkweb.ledger.LedgerResponses.AccountResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.AdjustmentResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.BillBalanceResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.CustomerAccountSummary;
import com.radhikafarms.milkweb.ledger.LedgerResponses.PaymentResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.RefundResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.ReversalResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.TransactionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** OWNER-only money operations (the /api/owner/** rule in SecurityConfig). The actor is always the signed-in owner. */
@RestController
@Validated
public class OwnerLedgerController {

    private static final int MAX_HISTORY = 500;

    private final LedgerService ledger;

    public OwnerLedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/api/owner/customers/{customerId}/payments")
    public ResponseEntity<PaymentResponse> recordPayment(
            @PathVariable UUID customerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RecordPaymentRequest request,
            @AuthenticationPrincipal MilkWebUserDetails owner) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ledger.recordPayment(customerId, request, idempotencyKey, owner.getUserId()));
    }

    @PostMapping("/api/owner/payments/{paymentId}/reversal")
    public ResponseEntity<ReversalResponse> reversePayment(
            @PathVariable UUID paymentId,
            @Valid @RequestBody ReversePaymentRequest request,
            @AuthenticationPrincipal MilkWebUserDetails owner) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledger.reversePayment(paymentId, request, owner.getUserId()));
    }

    @PostMapping("/api/owner/customers/{customerId}/adjustments")
    public ResponseEntity<AdjustmentResponse> recordAdjustment(
            @PathVariable UUID customerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RecordAdjustmentRequest request,
            @AuthenticationPrincipal MilkWebUserDetails owner) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ledger.recordAdjustment(customerId, request, idempotencyKey, owner.getUserId()));
    }

    @PostMapping("/api/owner/customers/{customerId}/refunds")
    public ResponseEntity<RefundResponse> recordRefund(
            @PathVariable UUID customerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RecordRefundRequest request,
            @AuthenticationPrincipal MilkWebUserDetails owner) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ledger.recordRefund(customerId, request, idempotencyKey, owner.getUserId()));
    }

    @GetMapping("/api/owner/customers/{customerId}/account")
    public AccountResponse account(@PathVariable UUID customerId) {
        return ledger.account(customerId);
    }

    @GetMapping("/api/owner/customers/{customerId}/transactions")
    public List<TransactionResponse> transactions(@PathVariable UUID customerId,
                                                  @RequestParam(defaultValue = "100") int limit) {
        return ledger.transactions(customerId, boundedLimit(limit), true);
    }

    @GetMapping("/api/owner/accounts")
    public List<CustomerAccountSummary> accounts() {
        return ledger.accountsWithBalances();
    }

    @GetMapping("/api/owner/billing-periods/{period}/balances")
    public List<BillBalanceResponse> periodBalances(@PathVariable String period) {
        try {
            return ledger.periodBalances(YearMonth.parse(period));
        } catch (DateTimeParseException ex) {
            throw new LedgerValidationException("Billing period must use YYYY-MM format");
        }
    }

    static int boundedLimit(int limit) {
        if (limit < 1 || limit > MAX_HISTORY) {
            throw new LedgerValidationException("limit must be between 1 and " + MAX_HISTORY);
        }
        return limit;
    }
}
