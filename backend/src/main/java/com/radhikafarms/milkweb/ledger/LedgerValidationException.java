package com.radhikafarms.milkweb.ledger;

/** Input that is well-formed but not allowed by a ledger rule (returned as 400). */
public class LedgerValidationException extends RuntimeException {
    public LedgerValidationException(String message) {
        super(message);
    }
}
