package com.radhikafarms.milkweb.customer;

import com.radhikafarms.milkweb.customer.exception.CustomerNotFoundException;
import com.radhikafarms.milkweb.customer.exception.PhoneAlreadyRegisteredException;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final UserService userService;
    private final Clock businessClock;

    public CustomerService(CustomerRepository customerRepository, UserService userService, Clock businessClock) {
        this.customerRepository = customerRepository;
        this.userService = userService;
        this.businessClock = businessClock;
    }

    @Transactional
    public Customer createCustomer(OwnerCreateCustomerRequest req) {
        return createCustomerAccount(
                req.phone(),
                req.password(),
                req.name(),
                req.address(),
                req.billingType() == null ? BillingType.MONTHLY : req.billingType(),
                req.status() == null ? CustomerStatus.ACTIVE : req.status(),
                req.joinDate() == null ? LocalDate.now() : req.joinDate()
        );
    }

    /**
     * Creates a self-registered CUSTOMER account. Role, status, billing type, and join date are fixed
     * server-side: the account starts PENDING until the owner approves it, and is billed MONTHLY by
     * default like owner-created customers.
     */
    @Transactional
    public Customer registerCustomer(String phone, String rawPassword, String name, String address) {
        return createCustomerAccount(
                phone,
                rawPassword,
                name.trim(),
                address == null || address.isBlank() ? null : address.trim(),
                BillingType.MONTHLY,
                CustomerStatus.PENDING,
                LocalDate.now(businessClock)
        );
    }

    /**
     * Creates the User (always CUSTOMER) and its Customer profile in the caller's transaction.
     *
     * <p>The pre-check gives a clean 409 in the common case. Two concurrent requests for the same phone
     * can both pass it, so the insert is flushed here and a violation of {@code uk_users_phone} is
     * translated to the same {@link PhoneAlreadyRegisteredException} instead of failing at commit.
     */
    private Customer createCustomerAccount(String phone, String rawPassword, String name, String address,
                                           BillingType billingType, CustomerStatus status, LocalDate joinDate) {
        if (userService.findByPhone(phone).isPresent()) {
            throw new PhoneAlreadyRegisteredException(phone);
        }

        // create user (this will hash password)
        User user = userService.createUser(phone, rawPassword, UserRole.CUSTOMER);

        Customer customer = new Customer();
        customer.setUser(user);
        customer.setName(name);
        customer.setPhone(phone);
        customer.setAddress(address);
        customer.setBillingType(billingType);
        customer.setStatus(status);
        customer.setJoinDate(joinDate);

        try {
            return customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException ex) {
            if (isUserPhoneConstraintViolation(ex)) {
                throw new PhoneAlreadyRegisteredException(phone);
            }
            throw ex;
        }
    }

    private boolean isUserPhoneConstraintViolation(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && violation.getConstraintName() != null
                    && violation.getConstraintName().toLowerCase().contains("uk_users_phone")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    public Customer getById(UUID customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found for id: " + customerId));
    }

    public java.util.List<Customer> listAll() {
        return customerRepository.findAll();
    }

    @Transactional
    public Customer updateCustomer(UUID customerId, OwnerUpdateCustomerRequest request) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found for id: " + customerId));

        customer.setName(request.name());
        customer.setAddress(request.address());
        customer.setBillingType(request.billingType());
        customer.setStatus(request.status());
        customer.setJoinDate(request.joinDate());

        return customer;
    }

    public Customer getByUserId(UUID userId) {
        return customerRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomerNotFoundException(userId));
    }
}
