package com.easytask.backend.runner;

import com.easytask.backend.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the one-shot production reseed end to end against the test DB:
 * the org and all four accounts exist, every account signs in directly with
 * the shared password (no forced change), and a broad dataset was created
 * through the service layer (so the permission-gated status transitions and
 * approvals actually succeed).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class EasyTaskReseedInitializerTest {

    @Autowired
    private EasyTaskReseedInitializer initializer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String[] EMPLOYEES = {
            "siba.ghazaly@gmail.com", "esraaaboisaa2@gmail.com", "sajdtaljahd4@gmail.com"
    };

    @Test
    void reseedBuildsTheOrgAllAccountsAndABroadDataset() throws Exception {
        initializer.reseed();

        // Admin signs in directly, no forced change.
        JsonNode adminLogin = login(EasyTaskReseedInitializer.ADMIN_EMAIL);
        assertThat(adminLogin.path("user").path("mustChangePassword").asBoolean()).isFalse();
        assertThat(adminLogin.path("user").path("role").asText()).isEqualTo("ORGANIZATION_ADMIN");
        String adminToken = adminLogin.path("accessToken").asText();

        // Every employee signs in directly with the shared password.
        for (String email : EMPLOYEES) {
            JsonNode login = login(email);
            assertThat(login.path("user").path("mustChangePassword").asBoolean())
                    .as("%s must log in without a forced change", email).isFalse();
            assertThat(login.path("user").path("role").asText()).isEqualTo("EMPLOYEE");
        }

        // Exactly four users, four projects, and a rich set of tasks.
        assertThat(totalItems(adminToken, "/api/v1/users")).isEqualTo(4);
        assertThat(totalItems(adminToken, "/api/v1/projects")).isEqualTo(4);
        assertThat(totalItems(adminToken, "/api/v1/tasks?size=100")).isGreaterThanOrEqualTo(14);
    }

    @Test
    void reseedIsRepeatableWipingThePreviousRun() throws Exception {
        initializer.reseed();
        initializer.reseed(); // second wipe+seed must not blow up on existing data

        assertThat(totalItems(login(EasyTaskReseedInitializer.ADMIN_EMAIL).path("accessToken").asText(),
                "/api/v1/users")).isEqualTo(4);
    }

    private JsonNode login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, EasyTaskReseedInitializer.PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int totalItems(String token, String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("totalItems").asInt();
    }
}
