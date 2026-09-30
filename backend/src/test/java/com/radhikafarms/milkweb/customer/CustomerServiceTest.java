package com.radhikafarms.milkweb.customer;

import com.radhikafarms.milkweb.customer.exception.CustomerNotFoundException;
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

    @Test
    void shouldUpdateCustomerProfileWithoutChangingUser() {
        Customer customer = customerService.createCustomer(new OwnerCreateCustomerRequest(
                "9876543211",
                "secret123",
                "Original Name",
                "Original Address",
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                LocalDate.of(2026, 1, 1)
        ));
        User associatedUser = customer.getUser();
        String originalPasswordHash = associatedUser.getPasswordHash();
        UserRole originalRole = associatedUser.getRole();

        Customer updated = customerService.updateCustomer(customer.getId(), new OwnerUpdateCustomerRequest(
                "Updated Name",
                "Updated Address",
                BillingType.PREPAID,
                CustomerStatus.PAUSED,
                LocalDate.of(2026, 2, 2)
        ));

        assertThat(updated.getName()).isEqualTo("Updated Name");
        assertThat(updated.getAddress()).isEqualTo("Updated Address");
        assertThat(updated.getBillingType()).isEqualTo(BillingType.PREPAID);
        assertThat(updated.getStatus()).isEqualTo(CustomerStatus.PAUSED);
        assertThat(updated.getJoinDate()).isEqualTo(LocalDate.of(2026, 2, 2));
        assertThat(updated.getPhone()).isEqualTo("9876543211");
        assertThat(updated.getUser()).isSameAs(associatedUser);
        assertThat(associatedUser.getPhone()).isEqualTo("9876543211");
        assertThat(associatedUser.getPasswordHash()).isEqualTo(originalPasswordHash);
        assertThat(associatedUser.getRole()).isEqualTo(originalRole);
    }

    @Test
    void shouldThrowWhenUpdatingUnknownCustomer() {
        assertThatThrownBy(() -> customerService.updateCustomer(
                java.util.UUID.randomUUID(),
                new OwnerUpdateCustomerRequest(
                        "Updated Name",
                        "Updated Address",
                        BillingType.MONTHLY,
                        CustomerStatus.ACTIVE,
                        LocalDate.of(2026, 2, 2)
                )))
                .isInstanceOf(CustomerNotFoundException.class);
    }
}
