package com.radhikafarms.milkweb.customer;

import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRepository;
import com.radhikafarms.milkweb.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class CustomerRepositoryTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private UserRepository userRepository;

    private User customerUser;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setPhone("9876543210");
        user.setPasswordHash("test-hash");
        user.setRole(UserRole.CUSTOMER);
        customerUser = userRepository.save(user);
    }

    @Test
    void shouldSaveCustomerLinkedToUser() {
        Customer customer = new Customer();
        customer.setUser(customerUser);
        customer.setName("Radhika Farms");
        customer.setPhone("9876543210");
        customer.setAddress("123 Dairy Lane, Pune");
        customer.setBillingType(BillingType.MONTHLY);
        customer.setStatus(CustomerStatus.ACTIVE);
        customer.setJoinDate(LocalDate.of(2026, 1, 15));

        Customer saved = customerRepository.save(customer);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUser().getId()).isEqualTo(customerUser.getId());
        assertThat(saved.getName()).isEqualTo("Radhika Farms");
        assertThat(saved.getBillingType()).isEqualTo(BillingType.MONTHLY);
        assertThat(saved.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(saved.getJoinDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void shouldFindCustomerByUserId() {
        Customer customer = new Customer();
        customer.setUser(customerUser);
        customer.setName("Radhika Farms");
        customer.setBillingType(BillingType.PREPAID);
        customer.setStatus(CustomerStatus.ACTIVE);
        customer.setJoinDate(LocalDate.of(2026, 2, 1));
        customerRepository.save(customer);

        var found = customerRepository.findByUserId(customerUser.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Radhika Farms");
        assertThat(found.get().getUser().getPhone()).isEqualTo("9876543210");
    }

    @Test
    void shouldEnforceOneCustomerProfilePerUser() {
        Customer first = new Customer();
        first.setUser(customerUser);
        first.setName("First Profile");
        first.setBillingType(BillingType.MONTHLY);
        first.setStatus(CustomerStatus.ACTIVE);
        first.setJoinDate(LocalDate.of(2026, 1, 1));
        customerRepository.save(first);

        Customer duplicate = new Customer();
        duplicate.setUser(customerUser);
        duplicate.setName("Duplicate Profile");
        duplicate.setBillingType(BillingType.MONTHLY);
        duplicate.setStatus(CustomerStatus.ACTIVE);
        duplicate.setJoinDate(LocalDate.of(2026, 1, 2));

        assertThatThrownBy(() -> customerRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
