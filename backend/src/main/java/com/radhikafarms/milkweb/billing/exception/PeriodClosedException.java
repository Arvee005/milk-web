package com.radhikafarms.milkweb.billing.exception;

import java.time.LocalDate;

public class PeriodClosedException extends RuntimeException {
    public PeriodClosedException(LocalDate periodStart) {
        super("Billing period is closed: " + periodStart);
    }
}
