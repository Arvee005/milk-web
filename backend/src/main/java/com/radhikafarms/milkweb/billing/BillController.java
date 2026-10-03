package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.auth.MilkWebUserDetails;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@Validated
public class BillController {

    private static final int MAX_PAGE_SIZE = 100;
    private final BillService billService;

    public BillController(BillService billService) {
        this.billService = billService;
    }

    @PostMapping("/api/owner/billing-periods/{period}/bills/{customerId}")
    public BillResponse generate(
            @PathVariable String period,
            @PathVariable UUID customerId
    ) {
        return billService.generateDraft(validatePeriod(period), customerId);
    }

    @GetMapping("/api/owner/bills/{billId}")
    public BillResponse getBill(@PathVariable UUID billId) {
        return billService.getBill(billId);
    }

    @GetMapping("/api/owner/bills")
    public Page<BillResponse> listBills(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page or size is outside the allowed range");
        }
        return billService.listBills(
                customerId,
                period == null ? null : validatePeriod(period),
                PageRequest.of(page, size, Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.asc("id"))));
    }

    @PostMapping("/api/owner/bills/{billId}/issue")
    public BillResponse issue(
            @PathVariable UUID billId,
            @AuthenticationPrincipal MilkWebUserDetails userDetails
    ) {
        return billService.issue(billId, userDetails.getUserId());
    }

    private String validatePeriod(String period) {
        if (period == null || !period.matches("[0-9]{4}-(0[1-9]|1[0-2])") || period.startsWith("0000-")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Period must be a valid YYYY-MM month");
        }
        return period;
    }
}
