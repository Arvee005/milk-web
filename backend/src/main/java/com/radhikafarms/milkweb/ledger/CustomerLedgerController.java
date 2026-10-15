package com.radhikafarms.milkweb.ledger;

import com.radhikafarms.milkweb.auth.MilkWebUserDetails;
import com.radhikafarms.milkweb.customer.CustomerService;
import com.radhikafarms.milkweb.ledger.LedgerResponses.AccountResponse;
import com.radhikafarms.milkweb.ledger.LedgerResponses.TransactionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Read-only money views for the signed-in CUSTOMER (the /api/customer/** rule). The customer is always derived
 * from the authenticated user; a supplied customerId is rejected rather than trusted.
 */
@RestController
public class CustomerLedgerController {

    private final LedgerService ledger;
    private final CustomerService customers;

    public CustomerLedgerController(LedgerService ledger, CustomerService customers) {
        this.ledger = ledger;
        this.customers = customers;
    }

    @GetMapping("/api/customer/account")
    public AccountResponse account(@AuthenticationPrincipal MilkWebUserDetails user,
                                   @RequestParam(required = false) UUID customerId) {
        rejectCustomerId(customerId);
        return ledger.account(customers.getByUserId(user.getUserId()).getId());
    }

    @GetMapping("/api/customer/transactions")
    public List<TransactionResponse> transactions(@AuthenticationPrincipal MilkWebUserDetails user,
                                                  @RequestParam(required = false) UUID customerId,
                                                  @RequestParam(defaultValue = "100") int limit) {
        rejectCustomerId(customerId);
        return ledger.transactions(customers.getByUserId(user.getUserId()).getId(),
                OwnerLedgerController.boundedLimit(limit), false);
    }

    private static void rejectCustomerId(UUID customerId) {
        if (customerId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer identity is derived from the authenticated account");
        }
    }
}
