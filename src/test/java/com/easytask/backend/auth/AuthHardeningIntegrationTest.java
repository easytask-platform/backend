package com.easytask.backend.auth;

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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class AuthHardeningIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueEmail() {
        return "hard" + COUNTER.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
    }

    private JsonNode registerOrganization(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "Hardening Org",
                                  "adminFullName": "Ava Smith",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode refresh(String refreshToken, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void replayingRotatedRefreshTokenRevokesAllSessions() throws Exception {
        String email = uniqueEmail();
        String original = registerOrganization(email).path("refreshToken").asText();

        // Legitimate rotation: original -> rotated
        String rotated = refresh(original, 200).path("refreshToken").asText();
        // Attacker replays the original (already rotated) token…
        refresh(original, 401);
        // …which must also kill the legitimate descendant token.
        refresh(rotated, 401);
    }

    @Test
    void deactivatedUserLosesAccessImmediately() throws Exception {
        String adminEmail = uniqueEmail();
        String adminToken = registerOrganization(adminEmail).path("accessToken").asText();

        String userEmail = uniqueEmail();
        MvcResult created = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Doomed User",
                                  "email": "%s",
                                  "initialPassword": "password123",
                                  "role": "EMPLOYEE"
                                }
                                """.formatted(userEmail)))
                .andExpect(status().isCreated())
                .andReturn();
        String userId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("id").asText();

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password123"}
                                """.formatted(userEmail)))
                .andExpect(status().isOk())
                .andReturn();
        String userAccess = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("accessToken").asText();

        // Works before deactivation…
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + userAccess))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/users/{id}/deactivate", userId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        // …and dies immediately after, even though the JWT itself is still valid.
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + userAccess))
                .andExpect(status().isUnauthorized());
    }
}
