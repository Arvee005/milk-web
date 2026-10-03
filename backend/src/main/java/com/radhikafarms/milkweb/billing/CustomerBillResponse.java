package com.radhikafarms.milkweb.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CustomerBillResponse(
        UUID id,
        String period,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal totalAmount,
        BillStatus status,
        Instant issuedAt,
        List<CustomerBillLineResponse> lines
) {
}
