package com.radhikafarms.milkweb.customer;

import java.time.LocalDate;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        UUID userId,
        String phone,
        String name,
        String address,
        BillingType billingType,
        CustomerStatus status,
        LocalDate joinDate
) {
}
