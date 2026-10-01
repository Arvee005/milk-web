package com.radhikafarms.milkweb.dailyrecord.exception;

public class DuplicateDailyRecordException extends RuntimeException {
    public DuplicateDailyRecordException() {
        super("A daily record already exists for this customer, product, date, and slot");
    }
}
