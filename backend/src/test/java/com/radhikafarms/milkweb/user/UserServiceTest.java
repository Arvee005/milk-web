package com.radhikafarms.milkweb.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Test
    void shouldCreateUserWithBcryptHashedPassword() {
        User user = userService.createUser("9876543210", "secret123", UserRole.CUSTOMER);

        assertThat(user.getId()).isNotNull();
        assertThat(user.getPhone()).isEqualTo("9876543210");
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.getPasswordHash()).isNotEqualTo("secret123");
        assertThat(user.getPasswordHash()).startsWith("$2a$");
        assertThat(userService.matchesPassword(user, "secret123")).isTrue();
        assertThat(userService.matchesPassword(user, "wrong-password")).isFalse();
    }

    @Test
    void shouldRejectDuplicatePhoneNumber() {
        userService.createUser("9876543210", "secret123", UserRole.CUSTOMER);

        assertThatThrownBy(() -> userService.createUser("9876543210", "other-pass", UserRole.OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void shouldFindUserByPhone() {
        userService.createUser("9876543210", "secret123", UserRole.OWNER);

        var found = userService.findByPhone("9876543210");

        assertThat(found).isPresent();
        assertThat(found.get().getRole()).isEqualTo(UserRole.OWNER);
    }
}
