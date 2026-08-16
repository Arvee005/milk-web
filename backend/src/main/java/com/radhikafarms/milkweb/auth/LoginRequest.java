package com.radhikafarms.milkweb.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(
        @NotBlank
        @Pattern(regexp = "\\d{10}", message = "Phone must be a 10-digit number")
        String phone,

        @NotBlank
        String password
) {
}
