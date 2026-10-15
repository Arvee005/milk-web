package com.radhikafarms.milkweb.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.radhikafarms.milkweb.customer.BillingType;
import com.radhikafarms.milkweb.customer.Customer;
import com.radhikafarms.milkweb.customer.CustomerRepository;
import com.radhikafarms.milkweb.customer.CustomerStatus;
import com.radhikafarms.milkweb.user.User;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Transactional
class RegistrationControllerTest {

    private static final String PHONE = "9123456780";
    private static final String PASSWORD = "customer-pass";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private CustomerRepository customerRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersPendingCustomerAndSignsIn() throws Exception {
        String response = register(validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.phone").value(PHONE))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(PASSWORD).doesNotContain("$2a$");

        User user = userService.findByPhone(PHONE).orElseThrow();
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.getPasswordHash()).startsWith("$2a$").isNotEqualTo(PASSWORD);

        Customer customer = customerRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(customer.getName()).isEqualTo("Asha Devi");
        assertThat(customer.getAddress()).isEqualTo("Near the temple");
        assertThat(customer.getPhone()).isEqualTo(PHONE);
        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.PENDING);
        assertThat(customer.getBillingType()).isEqualTo(BillingType.MONTHLY);
        assertThat(customer.getJoinDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void registeredTokenReachesOnlyTheCustomersOwnWorkspace() throws Exception {
        String token = tokenFrom(register(validBody()).andExpect(status().isCreated()));

        mockMvc.perform(get("/api/customer/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(PHONE))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
        mockMvc.perform(get("/api/owner/customers").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void registeredCustomerCanSignInAfterwards() throws Exception {
        register(validBody()).andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(PHONE, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void ignoresClientSuppliedRoleIdsAndAccountState() throws Exception {
        Map<String, Object> body = validBody();
        body.put("role", "OWNER");
        body.put("userId", "00000000-0000-0000-0000-000000000001");
        body.put("id", "00000000-0000-0000-0000-000000000002");
        body.put("status", "ACTIVE");
        body.put("billingType", "PREPAID");
        body.put("joinDate", "2020-01-01");

        register(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        User user = userService.findByPhone(PHONE).orElseThrow();
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.getId().toString()).isNotEqualTo("00000000-0000-0000-0000-000000000001");
        Customer customer = customerRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.PENDING);
        assertThat(customer.getBillingType()).isEqualTo(BillingType.MONTHLY);
        assertThat(customer.getJoinDate()).isNotEqualTo(LocalDate.of(2020, 1, 1));
    }

    @Test
    void rejectsPhoneAlreadyRegisteredWithoutChangingTheExistingAccount() throws Exception {
        userService.createUser(PHONE, "original-pass", UserRole.CUSTOMER);

        register(validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("already registered")));

        User user = userService.findByPhone(PHONE).orElseThrow();
        assertThat(userService.matchesPassword(user, "original-pass")).isTrue();
        assertThat(userService.matchesPassword(user, PASSWORD)).isFalse();
    }

    @Test
    void cannotTakeOverTheOwnerPhone() throws Exception {
        userService.createUser("9000000001", "ownerpass", UserRole.OWNER);
        Map<String, Object> body = validBody();
        body.put("phone", "9000000001");

        register(body).andExpect(status().isConflict());

        User owner = userService.findByPhone("9000000001").orElseThrow();
        assertThat(owner.getRole()).isEqualTo(UserRole.OWNER);
        assertThat(userService.matchesPassword(owner, "ownerpass")).isTrue();
        assertThat(customerRepository.findByUserId(owner.getId())).isEmpty();
    }

    @Test
    void ownerCannotStartSubscriptionForPendingCustomer() throws Exception {
        userService.createUser("9000000001", "ownerpass", UserRole.OWNER);
        String ownerToken = tokenFrom(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest("9000000001", "ownerpass")))));
        register(validBody()).andExpect(status().isCreated());
        Customer customer = customerRepository.findByUserId(userService.findByPhone(PHONE).orElseThrow().getId())
                .orElseThrow();

        String productJson = mockMvc.perform(post("/api/owner/products")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cow Milk\",\"unit\":\"LITRE\",\"price\":60.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String productId = objectMapper.readTree(productJson).get("id").asText();

        mockMvc.perform(post("/api/owner/subscriptions")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customer.getId().toString(),
                                "productId", productId,
                                "defaultQuantity", 1,
                                "startDate", LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Customer must be ACTIVE"));
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        assertRejected("phone", "912345678", "phone");
        assertRejected("phone", "91234567ab", "phone");
        assertRejected("phone", null, "phone");
        assertRejected("password", "short12", "password");
        assertRejected("password", "a".repeat(73), "Password must be at most 72 bytes");
        assertRejected("password", "€".repeat(25), "Password must be at most 72 bytes");
        assertRejected("password", null, "password");
        assertRejected("name", "   ", "name");
        assertRejected("name", "n".repeat(101), "name");
        assertRejected("address", "a".repeat(501), "address");

        assertThat(userService.findByPhone(PHONE)).isEmpty();
    }

    @Test
    void acceptsMaximumLengthPasswordAndMissingAddress() throws Exception {
        Map<String, Object> body = validBody();
        body.put("password", "a".repeat(72));
        body.remove("address");

        register(body).andExpect(status().isCreated());

        User user = userService.findByPhone(PHONE).orElseThrow();
        assertThat(customerRepository.findByUserId(user.getId()).orElseThrow().getAddress()).isNull();
    }

    private void assertRejected(String field, Object value, String expectedMessagePart) throws Exception {
        Map<String, Object> body = validBody();
        body.put(field, value);
        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString(expectedMessagePart)));
    }

    private Map<String, Object> validBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("phone", PHONE);
        body.put("password", PASSWORD);
        body.put("name", "  Asha Devi ");
        body.put("address", "Near the temple");
        return body;
    }

    private ResultActions register(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String tokenFrom(ResultActions result) throws Exception {
        String response = result.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }
}
