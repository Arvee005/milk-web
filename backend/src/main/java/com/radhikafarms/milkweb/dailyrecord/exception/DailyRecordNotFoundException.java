package com.radhikafarms.milkweb.dailyrecord.exception;

import java.util.UUID;

public class DailyRecordNotFoundException extends RuntimeException {
    public DailyRecordNotFoundException(UUID dailyRecordId) {
        super("Daily record not found: " + dailyRecordId);
    }
}
