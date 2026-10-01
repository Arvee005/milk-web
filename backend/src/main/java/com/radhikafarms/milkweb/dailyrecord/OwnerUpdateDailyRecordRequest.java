package com.radhikafarms.milkweb.dailyrecord;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record OwnerUpdateDailyRecordRequest(
        @NotNull @Digits(integer = 5, fraction = 3) BigDecimal quantity,
        @NotNull DailyRecordStatus status,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPriceSnapshot
) {
}
