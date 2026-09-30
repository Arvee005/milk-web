package com.radhikafarms.milkweb.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record OwnerUpdateCustomerRequest(
        @NotBlank
        String name,

        String address,

        @NotNull
        BillingType billingType,

        @NotNull
        CustomerStatus status,

        @NotNull
        LocalDate joinDate
) {
}
