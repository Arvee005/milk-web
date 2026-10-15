package com.radhikafarms.milkweb.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

/**
 * Public customer self-registration request.
 *
 * <p>Deliberately has no role, user ID, customer ID, status, billing type, or join date: the server
 * decides all of them, so a visitor cannot register as OWNER or pick their own account state.
 */
public record RegisterCustomerRequest(
        @NotBlank
        @Pattern(regexp = "\\d{10}", message = "Phone must be a 10-digit number")
        String phone,

        @NotBlank
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        @NotBlank
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Size(max = 500, message = "Address must be at most 500 characters")
        String address
) {

    // BCrypt only hashes the first 72 bytes and Spring's encoder rejects longer input, so the limit is
    // enforced here as a 400 rather than surfacing as a server error. Measured in UTF-8 bytes because a
    // character count understates multi-byte passwords.
    private static final int MAX_PASSWORD_BYTES = 72;

    @JsonIgnore
    @AssertTrue(message = "Password must be at most 72 bytes")
    public boolean isPasswordWithinLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }
}
