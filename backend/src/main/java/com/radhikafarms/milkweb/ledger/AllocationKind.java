package com.radhikafarms.milkweb.ledger;

/** ALLOCATE applies credit to a bill; RELEASE takes it back after a payment reversal. */
public enum AllocationKind {
    ALLOCATE,
    RELEASE
}
