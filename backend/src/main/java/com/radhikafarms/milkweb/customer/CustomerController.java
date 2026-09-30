package com.radhikafarms.milkweb.customer;

import com.radhikafarms.milkweb.auth.MilkWebUserDetails;
import com.radhikafarms.milkweb.customer.exception.PhoneAlreadyRegisteredException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;

@RestController
@Validated
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping("/api/owner/customers")
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody OwnerCreateCustomerRequest request) {
        try {
            Customer created = customerService.createCustomer(request);
            CustomerResponse response = toResponse(created);
            URI location = URI.create("/api/owner/customers/" + created.getId());
            return ResponseEntity.created(location).body(response);
        } catch (PhoneAlreadyRegisteredException ex) {
            throw ex; // handled by global exception handler
        }
    }

    @GetMapping("/api/customer/me")
    public CustomerResponse me(@AuthenticationPrincipal MilkWebUserDetails userDetails) {
        UUID userId = userDetails.getUserId();
        Customer customer = customerService.getByUserId(userId);
        return toResponse(customer);
    }

    @GetMapping("/api/owner/customers/{customerId}")
    public CustomerResponse getCustomerById(@PathVariable UUID customerId) {
        Customer customer = customerService.getById(customerId);
        return toResponse(customer);
    }

    @GetMapping("/api/owner/customers")
    public java.util.List<CustomerResponse> listCustomers() {
        java.util.List<Customer> customers = customerService.listAll();
        return customers.stream().map(this::toResponse).toList();
    }

    @PutMapping("/api/owner/customers/{customerId}")
    public CustomerResponse updateCustomer(
            @PathVariable UUID customerId,
            @Valid @RequestBody OwnerUpdateCustomerRequest request
    ) {
        return toResponse(customerService.updateCustomer(customerId, request));
    }

    private CustomerResponse toResponse(Customer c) {
        return new CustomerResponse(
                c.getId(),
                c.getUser().getId(),
                c.getPhone(),
                c.getName(),
                c.getAddress(),
                c.getBillingType(),
                c.getStatus(),
                c.getJoinDate()
        );
    }
}
