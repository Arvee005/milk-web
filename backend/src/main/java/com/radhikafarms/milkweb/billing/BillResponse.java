package com.radhikafarms.milkweb.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BillResponse(
        UUID id,
        UUID customerId,
        String customerName,
        String customerPhone,
        String customerAddress,
        String period,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal totalAmount,
        BillStatus status,
        Instant createdAt,
        Instant issuedAt,
        UUID issuedByUserId,
        List<BillLineResponse> lines
) {
}
