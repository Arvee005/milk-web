package com.radhikafarms.milkweb.ledger;

/** The event that caused an allocation row, kept for the audit trail. */
public enum AllocationTrigger {
    PAYMENT,
    ADJUSTMENT,
    BILL_ISSUE,
    PAYMENT_REVERSAL
}
