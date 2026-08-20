package com.radhikafarms.milkweb.customer;

import com.radhikafarms.milkweb.customer.exception.PhoneAlreadyRegisteredException;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class CustomerServiceTest {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private UserService userService;

    @Test
    void shouldCreateCustomerAndUser() {
        OwnerCreateCustomerRequest req = new OwnerCreateCustomerRequest(
                "9876543210",
                "secret123",
                "Test Customer",
                "Some Address",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                LocalDate.now()
        );

        var customer = customerService.createCustomer(req);

        assertThat(customer.getId()).isNotNull();
        assertThat(customer.getName()).isEqualTo("Test Customer");
        assertThat(customer.getPhone()).isEqualTo("9876543210");
        assertThat(customer.getUser()).isNotNull();

        User user = userService.findByPhone("9876543210").orElseThrow();
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.getPasswordHash()).isNotEqualTo("secret123");
        assertThat(user.getPasswordHash()).startsWith("$2a$");
    }

    @Test
    void shouldRejectDuplicatePhone() {
        userService.createUser("9876543210", "secret123", UserRole.CUSTOMER);

        OwnerCreateCustomerRequest req = new OwnerCreateCustomerRequest(
                "9876543210",
                "anotherpass",
                "Name",
                "Addr",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                LocalDate.now()
        );

        assertThatThrownBy(() -> customerService.createCustomer(req))
                .isInstanceOf(PhoneAlreadyRegisteredException.class)
                .hasMessageContaining("already registered");
    }
}
