package com.radhikafarms.milkweb.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public AuthService.LoginResponse login(@RequestBody @Validated LoginRequest request,
                                           HttpServletRequest httpRequest) {
        return authService.login(request.phone(), request.password(), ClientIpResolver.resolve(httpRequest));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthService.LoginResponse> register(@RequestBody @Validated RegisterCustomerRequest request,
                                                              HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.register(request, ClientIpResolver.resolve(httpRequest)));
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal MilkWebUserDetails userDetails) {
        return Map.of(
                "userId", userDetails.getUserId(),
                "phone", userDetails.getUsername(),
                "role", userDetails.getUser().getRole().name()
        );
    }

    @GetMapping("/owner-check")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, String> ownerCheck() {
        return Map.of("access", "owner");
    }

    @GetMapping("/customer-check")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, String> customerCheck() {
        return Map.of("access", "customer");
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<Map<String, String>> handleTooManyAttempts(TooManyAttemptsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", ex.getMessage()));
    }
}
