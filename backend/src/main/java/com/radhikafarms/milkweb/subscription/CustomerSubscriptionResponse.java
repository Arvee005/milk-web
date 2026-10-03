package com.radhikafarms.milkweb.subscription;

import com.radhikafarms.milkweb.product.ProductUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerSubscriptionResponse(
        UUID id,
        String productName,
        ProductUnit productUnit,
        BigDecimal defaultQuantity,
        SubscriptionStatus status,
        LocalDate startDate,
        LocalDate endDate,
        boolean effectiveToday,
        SubscriptionPeriodState periodState
) {
}
