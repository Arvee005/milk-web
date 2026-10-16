package com.radhikafarms.milkweb.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
// Shows the parts of each health check so the test can prove what liveness includes.
@TestPropertySource(properties = {
        "management.endpoint.health.group.liveness.show-components=always",
        "management.endpoint.health.group.readiness.show-components=always"
})
class HealthProbeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void livenessIsPublicAndDoesNotIncludeTheDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.livenessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db").doesNotExist());
        // Readiness does check the database, which is how a real outage still shows up.
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void healthAndReadinessArePublicButOtherActuatorRoutesAreNot() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/actuator/health/liveness")).andExpect(status().isUnauthorized());
    }
}
