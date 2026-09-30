package com.radhikafarms.milkweb.subscription;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OwnerUpdateSubscriptionRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 5, fraction = 3)
        BigDecimal defaultQuantity,
        @NotNull LocalDate startDate,
        LocalDate endDate,
        @NotNull SubscriptionStatus status
) {
    @AssertTrue(message = "endDate must be on or after startDate")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
}
