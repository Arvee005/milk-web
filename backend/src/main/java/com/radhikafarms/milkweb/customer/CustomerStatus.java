package com.radhikafarms.milkweb.customer;

public enum CustomerStatus {
    ACTIVE,
    PAUSED,
    INACTIVE,
    /**
     * Self-registered and awaiting owner approval. The customer can sign in and view their own
     * (empty) account, but subscriptions and daily records require ACTIVE, so nothing is supplied
     * or billed until the owner changes the status.
     */
    PENDING
}
