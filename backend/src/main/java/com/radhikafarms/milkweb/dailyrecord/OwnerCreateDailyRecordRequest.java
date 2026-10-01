package com.radhikafarms.milkweb.dailyrecord;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record OwnerCreateDailyRecordRequest(
        @NotNull UUID customerId,
        @NotNull UUID productId,
        UUID subscriptionId,
        @NotNull @PastOrPresent LocalDate recordDate,
        @NotNull DailyRecordSlot slot,
        @NotNull @Digits(integer = 5, fraction = 3) BigDecimal quantity,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPriceSnapshot,
        @NotNull DailyRecordStatus status
) {
    @AssertTrue(message = "Quantity and price must match the DailyRecord status")
    public boolean isStatusValuesValid() {
        if (quantity == null || status == null) {
            return true;
        }
        if (status == DailyRecordStatus.COLLECTED || status == DailyRecordStatus.EXTRA) {
            return quantity.signum() > 0 && unitPriceSnapshot != null;
        }
        return quantity.signum() == 0 && unitPriceSnapshot == null;
    }
}
