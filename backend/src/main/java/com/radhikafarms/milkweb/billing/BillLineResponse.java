package com.radhikafarms.milkweb.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BillLineResponse(
        UUID id,
        UUID dailyRecordId,
        LocalDate recordDate,
        String slot,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount
) {
}
