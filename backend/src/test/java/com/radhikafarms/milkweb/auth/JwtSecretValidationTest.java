package com.radhikafarms.milkweb.auth;

import com.radhikafarms.milkweb.config.JwtProperties;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretValidationTest {

    @Test
    void shouldRejectBlankSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("   ");
        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MILKWEB_JWT_SECRET is missing or blank");
    }

    @Test
    void shouldRejectNullSecret() {
        JwtProperties properties = new JwtProperties();
        // secret defaults to null
        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MILKWEB_JWT_SECRET is missing or blank");
    }

    @Test
    void shouldRejectSecretShorterThan256Bits() {
        JwtProperties properties = new JwtProperties();
        // 31 bytes is one short of the HS256 minimum
        properties.setSecret("a".repeat(31));
        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MILKWEB_JWT_SECRET is too short for HS256")
                .hasMessageContaining("31 bytes");
    }

    @Test
    void shouldAcceptSecretAtExactly256Bits() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("a".repeat(32));
        // Should not throw.
        new JwtService(properties);
    }

    @Test
    void shouldAcceptStrongSecretAndIssueToken() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm");
        JwtService jwtService = new JwtService(properties);

        User user = new User();
        user.setPhone("9876543210");
        user.setRole(UserRole.CUSTOMER);
        setUserId(user, java.util.UUID.randomUUID());

        String token = jwtService.generateToken(user);
        assertThat(token).isNotBlank();
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    private void setUserId(User user, java.util.UUID id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}