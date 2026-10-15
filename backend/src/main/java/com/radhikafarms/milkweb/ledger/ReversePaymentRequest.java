package com.radhikafarms.milkweb.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversePaymentRequest(
        @NotBlank(message = "Give a reason for the correction")
        @Size(max = 255, message = "Reason must be at most 255 characters") String reason
) {
}
