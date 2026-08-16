package com.radhikafarms.milkweb.auth;

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

    public AuthService(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String phone, String password) {
        User user = userService.findByPhone(phone)
                .orElseThrow(() -> new BadCredentialsException("Invalid phone or password"));

        if (!userService.matchesPassword(user, password)) {
            throw new BadCredentialsException("Invalid phone or password");
        }

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
