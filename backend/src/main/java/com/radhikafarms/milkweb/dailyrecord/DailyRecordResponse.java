package com.radhikafarms.milkweb.dailyrecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DailyRecordResponse(
        UUID id,
        UUID customerId,
        UUID productId,
        UUID subscriptionId,
        LocalDate recordDate,
        DailyRecordSlot slot,
        BigDecimal quantity,
        BigDecimal unitPriceSnapshot,
        BigDecimal amount,
        DailyRecordStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
