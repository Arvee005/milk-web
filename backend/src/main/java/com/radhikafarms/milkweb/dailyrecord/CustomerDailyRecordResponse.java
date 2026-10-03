package com.radhikafarms.milkweb.dailyrecord;

import com.radhikafarms.milkweb.product.ProductUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerDailyRecordResponse(
        UUID id,
        LocalDate recordDate,
        DailyRecordSlot slot,
        String productName,
        ProductUnit productUnit,
        BigDecimal quantity,
        BigDecimal unitPriceSnapshot,
        BigDecimal amount,
        DailyRecordStatus status
) {
}
