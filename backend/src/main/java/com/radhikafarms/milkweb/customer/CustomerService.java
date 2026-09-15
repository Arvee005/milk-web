package com.radhikafarms.milkweb.customer;

import com.radhikafarms.milkweb.customer.exception.CustomerNotFoundException;
import com.radhikafarms.milkweb.customer.exception.PhoneAlreadyRegisteredException;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final UserService userService;

    public CustomerService(CustomerRepository customerRepository, UserService userService) {
        this.customerRepository = customerRepository;
        this.userService = userService;
    }

    @Transactional
    public Customer createCustomer(OwnerCreateCustomerRequest req) {
        // check phone uniqueness
        var existing = userService.findByPhone(req.phone());
        if (existing.isPresent()) {
            throw new PhoneAlreadyRegisteredException(req.phone());
        }

        // create user (this will hash password)
        User user = userService.createUser(req.phone(), req.password(), UserRole.CUSTOMER);

        // assemble customer entity
        Customer customer = new Customer();
        customer.setUser(user);
        customer.setName(req.name());
        customer.setPhone(req.phone());
        customer.setAddress(req.address());
        customer.setBillingType(req.billingType() == null ? BillingType.MONTHLY : req.billingType());
        customer.setStatus(req.status() == null ? CustomerStatus.ACTIVE : req.status());
        customer.setJoinDate(req.joinDate() == null ? LocalDate.now() : req.joinDate());

        Customer saved = customerRepository.save(customer);
        return saved;
    }

    public Customer getById(UUID customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found for id: " + customerId));
    }

    public java.util.List<Customer> listAll() {
        return customerRepository.findAll();
    }

    public Customer getByUserId(UUID userId) {
        return customerRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomerNotFoundException(userId));
    }
}
