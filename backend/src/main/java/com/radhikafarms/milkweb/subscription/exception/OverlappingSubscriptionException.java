package com.radhikafarms.milkweb.subscription.exception;

public class OverlappingSubscriptionException extends SubscriptionConflictException {
    public OverlappingSubscriptionException() {
        super("An active subscription already overlaps this customer and product date range");
    }
}
