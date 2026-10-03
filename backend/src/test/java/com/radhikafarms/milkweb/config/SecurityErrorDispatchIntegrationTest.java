package com.radhikafarms.milkweb.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.radhikafarms.milkweb.auth.JwtService;
import com.radhikafarms.milkweb.customer.BillingType;
import com.radhikafarms.milkweb.customer.CustomerService;
import com.radhikafarms.milkweb.customer.CustomerStatus;
import com.radhikafarms.milkweb.customer.OwnerCreateCustomerRequest;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityErrorDispatchIntegrationTest {

    private static final AtomicLong PHONE_SEQUENCE = new AtomicLong(1_000_000_000L);

    @LocalServerPort
    private int port;

    @Autowired
    private UserService userService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private JwtService jwtService;

    private String ownerToken;
    private String customerToken;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void createIsolatedTestUsers() {
        String ownerPhone = nextPhone();
        ownerToken = jwtService.generateToken(userService.createUser(ownerPhone, "test-password", UserRole.OWNER));

        String customerPhone = nextPhone();
        customerService.createCustomer(new OwnerCreateCustomerRequest(
                customerPhone,
                "test-password",
                "Security Dispatch Test Customer",
                null,
                BillingType.MONTHLY,
                CustomerStatus.ACTIVE,
                null
        ));
        customerToken = jwtService.generateToken(userService.findByPhone(customerPhone).orElseThrow());
    }

    @Test
    void customerOwnerDenialRemainsForbiddenAfterErrorDispatch() throws Exception {
        HttpResponse<String> response = get(
                "/api/owner/billing-periods/2042-03",
                customerToken
        );

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void invalidOwnerPeriodRemainsBadRequestAfterErrorDispatch() throws Exception {
        HttpResponse<String> response = get(
                "/api/owner/billing-periods/2026-13",
                ownerToken
        );

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void unauthenticatedOwnerRequestRemainsUnauthorized() throws Exception {
        HttpResponse<String> response = get("/api/owner/billing-periods/2042-03", null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void validOwnerPeriodRequestStillSucceeds() throws Exception {
        HttpResponse<String> response = get(
                "/api/owner/billing-periods/2042-03",
                ownerToken
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"period\":\"2042-03\"");
    }

    @Test
    void ownerCanSignInAndValidateSessionOverHttp() throws Exception {
        String phone = nextPhone();
        userService.createUser(phone, "integration-test-password", UserRole.OWNER);
        HttpRequest loginRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"phone\":\"" + phone + "\",\"password\":\"integration-test-password\"}"
                ))
                .build();

        HttpResponse<String> loginResponse = httpClient.send(
                loginRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(loginResponse.statusCode()).isEqualTo(200);
        JsonNode loginBody = objectMapper.readTree(loginResponse.body());
        String token = loginBody.required("token").asText();
        assertThat(loginBody.required("role").asText()).isEqualTo("OWNER");

        HttpResponse<String> currentUserResponse = get("/api/auth/me", token);

        assertThat(currentUserResponse.statusCode()).isEqualTo(200);
        assertThat(currentUserResponse.body())
                .contains("\"userId\":\"" + loginBody.required("userId").asText() + "\"")
                .contains("\"phone\":\"" + phone + "\"")
                .contains("\"role\":\"OWNER\"");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String nextPhone() {
        return Long.toString(PHONE_SEQUENCE.getAndIncrement());
    }
}
