package com.radhikafarms.milkweb.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CustomerBillLineResponse(
        LocalDate recordDate,
        String slot,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount
) {
}
