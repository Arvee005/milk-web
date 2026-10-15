package com.radhikafarms.milkweb.ledger;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecordRefundRequest(
        @NotNull @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        @Digits(integer = 10, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount,
        @NotNull PaymentMethod method,
        @NotBlank(message = "Give a reason") @Size(max = 255, message = "Reason must be at most 255 characters")
        String reason,
        LocalDate refundedOn
) {
}
