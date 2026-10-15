package com.radhikafarms.milkweb.auth;

import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.customer.CustomerService;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {

    private final UserService userService;
    private final JwtService jwtService;
    private final CustomerService customerService;
    private final AuthRateLimiter rateLimiter;

    public AuthService(UserService userService, JwtService jwtService, CustomerService customerService,
                       AuthRateLimiter rateLimiter) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.customerService = customerService;
        this.rateLimiter = rateLimiter;
    }

    public LoginResponse login(String phone, String password, String clientIp) {
        // Checked before the password so a blocked caller cannot keep guessing (or keep the server hashing).
        rateLimiter.checkLogin(phone, clientIp);

        User user = userService.findByPhone(phone).orElse(null);
        if (user == null || !userService.matchesPassword(user, password)) {
            rateLimiter.recordLoginFailure(phone, clientIp);
            throw new BadCredentialsException("Invalid phone or password");
        }

        rateLimiter.recordLoginSuccess(phone);
        String token = jwtService.generateToken(user);
        return new LoginResponse(token, user.getId(), user.getPhone(), user.getRole());
    }

    /**
     * Registers a CUSTOMER account and signs it in, returning the same response as {@link #login}.
     * The role is never taken from the request.
     */
    public LoginResponse register(RegisterCustomerRequest request, String clientIp) {
        rateLimiter.checkAndRecordRegistration(clientIp);
        Customer customer = customerService.registerCustomer(
                request.phone(), request.password(), request.name(), request.address());
        User user = customer.getUser();
        String token = jwtService.generateToken(user);
        return new LoginResponse(token, user.getId(), user.getPhone(), user.getRole());
    }

    public record LoginResponse(
            String token,
            UUID userId,
            String phone,
            UserRole role
    ) {
    }
}
