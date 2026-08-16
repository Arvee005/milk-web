package com.radhikafarms.milkweb.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldLoginWithValidCredentials() throws Exception {
        userService.createUser("9876543210", "secret123", UserRole.CUSTOMER);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("9876543210", "secret123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.phone").value("9876543210"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void shouldRejectInvalidCredentials() throws Exception {
        userService.createUser("9876543210", "secret123", UserRole.CUSTOMER);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("9876543210", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid phone or password"));
    }

    @Test
    void shouldRejectUnknownPhone() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("9876543210", "secret123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid phone or password"));
    }

    @Test
    void shouldReturnCurrentUserForAuthenticatedRequest() throws Exception {
        userService.createUser("9876543210", "secret123", UserRole.OWNER);

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("9876543210", "secret123"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readTree(response).get("token").asText();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("9876543210"))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void ownerShouldAccessOwnerOnlyEndpoint() throws Exception {
        userService.createUser("9876543210", "secret123", UserRole.OWNER);
        String token = loginAndGetToken("9876543210", "secret123");

        mockMvc.perform(get("/api/auth/owner-check")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access").value("owner"));
    }

    @Test
    void customerShouldNotAccessOwnerOnlyEndpoint() throws Exception {
        userService.createUser("9876543210", "secret123", UserRole.CUSTOMER);
        String token = loginAndGetToken("9876543210", "secret123");

        mockMvc.perform(get("/api/auth/owner-check")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerShouldAccessCustomerOnlyEndpoint() throws Exception {
        userService.createUser("9876543210", "secret123", UserRole.CUSTOMER);
        String token = loginAndGetToken("9876543210", "secret123");

        mockMvc.perform(get("/api/auth/customer-check")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access").value("customer"));
    }

    @Test
    void ownerShouldNotAccessCustomerOnlyEndpoint() throws Exception {
        userService.createUser("9876543210", "secret123", UserRole.OWNER);
        String token = loginAndGetToken("9876543210", "secret123");

        mockMvc.perform(get("/api/auth/customer-check")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectUnauthenticatedAccessToProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    private String loginAndGetToken(String phone, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(phone, password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("token").asText();
    }
}
