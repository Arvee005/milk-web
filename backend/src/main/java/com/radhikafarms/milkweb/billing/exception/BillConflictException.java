package com.radhikafarms.milkweb.billing.exception;

public class BillConflictException extends RuntimeException {
    public BillConflictException(String message) {
        super(message);
    }
}
