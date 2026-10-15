package com.radhikafarms.milkweb.ledger;

public class LedgerNotFoundException extends RuntimeException {
    public LedgerNotFoundException(String message) {
        super(message);
    }
}
