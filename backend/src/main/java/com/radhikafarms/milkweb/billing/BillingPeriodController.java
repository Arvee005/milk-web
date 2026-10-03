package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.auth.MilkWebUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Validated
public class BillingPeriodController {

    private static final String PERIOD_PATTERN = "[0-9]{4}-(0[1-9]|1[0-2])";

    private final BillingPeriodService billingPeriodService;

    public BillingPeriodController(BillingPeriodService billingPeriodService) {
        this.billingPeriodService = billingPeriodService;
    }

    @GetMapping("/api/owner/billing-periods/{period}")
    public BillingPeriodResponse getPeriod(@PathVariable String period) {
        return billingPeriodService.getPeriod(validatePeriod(period));
    }

    @PostMapping("/api/owner/billing-periods/{period}/close")
    public BillingPeriodResponse closePeriod(
            @PathVariable String period,
            @AuthenticationPrincipal MilkWebUserDetails userDetails
    ) {
        return billingPeriodService.closePeriod(validatePeriod(period), userDetails.getUserId());
    }

    private String validatePeriod(String period) {
        if (period == null || !period.matches(PERIOD_PATTERN) || period.startsWith("0000-")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Period must be a valid YYYY-MM month");
        }
        return period;
    }
}
