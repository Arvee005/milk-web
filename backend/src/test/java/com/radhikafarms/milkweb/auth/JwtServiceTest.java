package com.radhikafarms.milkweb.auth;

import com.radhikafarms.milkweb.config.JwtProperties;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm");
        properties.setExpirationMs(3600000);
        jwtService = new JwtService(properties);
    }

    @Test
    void shouldGenerateAndValidateToken() {
        User user = new User();
        user.setPhone("9876543210");
        user.setRole(UserRole.CUSTOMER);
        UUID userId = UUID.randomUUID();
        setUserId(user, userId);

        String token = jwtService.generateToken(user);

        assertThat(token).isNotBlank();
        assertThat(jwtService.isTokenValid(token)).isTrue();
        assertThat(jwtService.extractUserId(token)).isEqualTo(userId);
        assertThat(jwtService.extractRole(token)).isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    void shouldRejectInvalidToken() {
        assertThat(jwtService.isTokenValid("not-a-valid-token")).isFalse();
    }

    private void setUserId(User user, UUID id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
