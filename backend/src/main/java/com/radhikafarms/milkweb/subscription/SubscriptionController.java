package com.radhikafarms.milkweb.subscription;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping("/api/owner/subscriptions")
    public ResponseEntity<SubscriptionResponse> createSubscription(
            @Valid @RequestBody OwnerCreateSubscriptionRequest request
    ) {
        SubscriptionResponse response = toResponse(subscriptionService.createSubscription(request));
        return ResponseEntity.created(URI.create("/api/owner/subscriptions/" + response.id())).body(response);
    }

    @GetMapping("/api/owner/subscriptions")
    public List<SubscriptionResponse> listSubscriptions(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) SubscriptionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveOn
    ) {
        return subscriptionService.listSubscriptions(customerId, productId, status, effectiveOn)
                .stream().map(this::toResponse).toList();
    }

    @GetMapping("/api/owner/subscriptions/{subscriptionId}")
    public SubscriptionResponse getSubscription(@PathVariable UUID subscriptionId) {
        return toResponse(subscriptionService.getSubscriptionById(subscriptionId));
    }

    @PutMapping("/api/owner/subscriptions/{subscriptionId}")
    public SubscriptionResponse updateSubscription(
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody OwnerUpdateSubscriptionRequest request
    ) {
        return toResponse(subscriptionService.updateSubscription(subscriptionId, request));
    }

    private SubscriptionResponse toResponse(Subscription subscription) {
        return new SubscriptionResponse(
                subscription.getId(),
                subscription.getCustomer().getId(),
                subscription.getProduct().getId(),
                subscription.getDefaultQuantity(),
                subscription.getStatus(),
                subscription.getStartDate(),
                subscription.getEndDate(),
                subscription.getCreatedAt(),
                subscription.getUpdatedAt()
        );
    }
}
