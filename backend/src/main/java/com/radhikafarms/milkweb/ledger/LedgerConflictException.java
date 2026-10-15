package com.radhikafarms.milkweb.ledger;

/** A money operation that would break a ledger rule (returned as 409). */
public class LedgerConflictException extends RuntimeException {
    public LedgerConflictException(String message) {
        super(message);
    }
}
