package com.radhikafarms.milkweb.subscription;

import com.radhikafarms.milkweb.auth.MilkWebUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
public class CustomerSubscriptionController {

    private final SubscriptionService subscriptionService;
    private final Clock businessClock;

    public CustomerSubscriptionController(SubscriptionService subscriptionService, Clock businessClock) {
        this.subscriptionService = subscriptionService;
        this.businessClock = businessClock;
    }

    @GetMapping("/api/customer/subscriptions")
    public List<CustomerSubscriptionResponse> listCustomerSubscriptions(
            @AuthenticationPrincipal MilkWebUserDetails userDetails,
            @RequestParam(required = false) UUID customerId
    ) {
        if (customerId != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Customer identity is derived from the authenticated account");
        }

        LocalDate today = LocalDate.now(businessClock);
        return subscriptionService.listCustomerSubscriptions(userDetails.getUserId())
                .stream()
                .map(subscription -> toResponse(subscription, today))
                .toList();
    }

    private CustomerSubscriptionResponse toResponse(Subscription subscription, LocalDate today) {
        SubscriptionPeriodState periodState = periodState(subscription, today);
        boolean effectiveToday = subscription.getStatus() == SubscriptionStatus.ACTIVE
                && periodState == SubscriptionPeriodState.IN_PERIOD;
        return new CustomerSubscriptionResponse(
                subscription.getId(),
                subscription.getProduct().getName(),
                subscription.getProduct().getUnit(),
                subscription.getDefaultQuantity(),
                subscription.getStatus(),
                subscription.getStartDate(),
                subscription.getEndDate(),
                effectiveToday,
                periodState
        );
    }

    private SubscriptionPeriodState periodState(Subscription subscription, LocalDate today) {
        if (subscription.getStartDate().isAfter(today)) {
            return SubscriptionPeriodState.UPCOMING;
        }
        if (subscription.getEndDate() != null && subscription.getEndDate().isBefore(today)) {
            return SubscriptionPeriodState.ENDED;
        }
        return SubscriptionPeriodState.IN_PERIOD;
    }
}
