package com.radhikafarms.milkweb.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BillingPeriodResponse(
        String period,
        LocalDate periodStart,
        LocalDate periodEnd,
        BillingPeriodStatus status,
        Instant createdAt,
        Instant closedAt,
        UUID closedByUserId,
        long chargeableRecordCount,
        BigDecimal chargeableAmount
) {
}
