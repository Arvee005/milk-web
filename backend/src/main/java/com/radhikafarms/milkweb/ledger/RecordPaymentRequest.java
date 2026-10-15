package com.radhikafarms.milkweb.ledger;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Money received. The customer, currency, recorder and allocation are decided by the server. */
public record RecordPaymentRequest(
        @NotNull @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        @Digits(integer = 10, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount,
        @NotNull PaymentMethod method,
        @Size(max = 64, message = "Reference must be at most 64 characters") String reference,
        @Size(max = 255, message = "Note must be at most 255 characters") String note,
        LocalDate receivedOn
) {
}
