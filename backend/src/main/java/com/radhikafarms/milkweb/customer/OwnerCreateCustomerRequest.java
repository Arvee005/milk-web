package com.radhikafarms.milkweb.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record OwnerCreateCustomerRequest(
        @NotBlank
        @Pattern(regexp = "\\d{10}", message = "Phone must be a 10-digit number")
        String phone,

        @NotBlank
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        @NotBlank
        String name,

        String address,

        BillingType billingType,

        CustomerStatus status,

        LocalDate joinDate
) {
}
