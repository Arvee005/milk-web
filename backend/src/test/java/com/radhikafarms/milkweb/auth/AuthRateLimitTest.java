package com.radhikafarms.milkweb.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.radhikafarms.milkweb.user.UserRole;
import com.radhikafarms.milkweb.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Own database: a separate context sharing "milktest" would drop its tables on shutdown.
        "spring.datasource.url=jdbc:h2:mem:milktest-auth-rate-limit",
        "milkweb.rate-limit.login-failures-per-phone=3",
        "milkweb.rate-limit.login-failures-per-ip=1000",
        "milkweb.rate-limit.registrations-per-ip=2"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Transactional
class AuthRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void blocksLoginForAPhoneAfterRepeatedWrongPasswordsEvenWithTheRightOne() throws Exception {
        userService.createUser("9123456780", "right-password", UserRole.CUSTOMER);

        for (int i = 0; i < 3; i++) {
            login("9123456780", "wrong-password", "10.0.0." + i).andExpect(status().isUnauthorized());
        }

        login("9123456780", "right-password", "10.0.0.99")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.error").value("Too many attempts. Please wait 15 minutes and try again."))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void otherAccountsCanStillSignInWhileOnePhoneIsBlocked() throws Exception {
        userService.createUser("9000000001", "ownerpass", UserRole.OWNER);
        for (int i = 0; i < 3; i++) {
            login("9123456780", "guess-" + i, "10.0.0.1").andExpect(status().isUnauthorized());
        }

        login("9000000001", "ownerpass", "10.0.0.1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void limitsRegistrationsFromOneClientIp() throws Exception {
        register("9123456780", "10.0.0.1").andExpect(status().isCreated());
        register("9123456781", "10.0.0.1").andExpect(status().isCreated());

        register("9123456782", "10.0.0.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        register("9123456783", "10.0.0.2").andExpect(status().isCreated());
    }

    private ResultActions login(String phone, String password, String clientIp) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .header("X-Forwarded-For", clientIp + ", 172.16.0.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(phone, password))));
    }

    private ResultActions register(String phone, String clientIp) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "phone", phone, "password", "customer-pass", "name", "Test Customer"))));
    }
}
