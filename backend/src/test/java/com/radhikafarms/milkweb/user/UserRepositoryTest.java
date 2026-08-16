package com.radhikafarms.milkweb.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldFindUserByPhone() {
        User user = new User();
        user.setPhone("9876543210");
        user.setPasswordHash("test-hash");
        user.setRole(UserRole.CUSTOMER);

        userRepository.save(user);

        var foundUser = userRepository.findByPhone("9876543210");

        assertThat(foundUser).isPresent();
        assertThat(foundUser.get().getPhone()).isEqualTo("9876543210");
    }
}