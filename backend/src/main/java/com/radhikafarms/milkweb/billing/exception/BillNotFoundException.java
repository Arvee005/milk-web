package com.radhikafarms.milkweb.billing.exception;

import java.util.UUID;

public class BillNotFoundException extends RuntimeException {
    public BillNotFoundException(UUID billId) {
        super("Bill not found for id: " + billId);
    }
}
