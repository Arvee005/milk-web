package com.radhikafarms.milkweb.customer.exception;

import java.util.UUID;

public class CustomerNotFoundException extends RuntimeException {
    public CustomerNotFoundException(UUID userId) {
        super("Customer not found for user: " + userId);
    }

    public CustomerNotFoundException(String message) {
        super(message);
    }
}
