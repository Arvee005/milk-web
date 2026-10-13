package com.radhikafarms.milkweb.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that cross-origin requests are permitted only for configured origins and
 * that preflight responses advertise the expected headers. Same-origin requests are
 * unaffected because CORS is additive, not restrictive.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CorsConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldPermitPreflightFromAllowedOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/login")
        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE,OPTIONS"));
    }

    @Test
    void shouldRejectPreflightFromDisallowedOrigin() throws Exception {
        // Spring Security returns 403 for an unauthenticated preflight from a
        // disallowed origin rather than silently passing it through.
        mockMvc.perform(options("/api/auth/login")
        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void shouldNotEchoCredentialsHeader() throws Exception {
        // Stateless Bearer-token auth does not use cookies, so credentials must
        // remain disabled even for allowed origins. Spring omits the header
        // entirely when allowCredentials is false.
        mockMvc.perform(options("/api/auth/login")
        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }
}
