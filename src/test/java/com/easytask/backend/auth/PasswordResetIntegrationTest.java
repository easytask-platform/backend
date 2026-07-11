package com.easytask.backend.auth;

import com.easytask.backend.TestDatabaseConfiguration;
import com.easytask.backend.user.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestDatabaseConfiguration.class, PasswordResetIntegrationTest.CapturingMailerConfig.class})
class PasswordResetIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final AtomicReference<String> LAST_TOKEN = new AtomicReference<>();
    private static final AtomicReference<String> LAST_RECIPIENT = new AtomicReference<>();

    @TestConfiguration
    static class CapturingMailerConfig {
        @Bean
        @Primary
        PasswordResetMailer capturingMailer() {
            return (AppUser user, String rawToken) -> {
                LAST_RECIPIENT.set(user.getEmail());
                LAST_TOKEN.set(rawToken);
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearCapture() {
        LAST_TOKEN.set(null);
        LAST_RECIPIENT.set(null);
    }

    private String uniqueEmail() {
        return "reset" + COUNTER.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
    }

    private void registerOrganization(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "Reset Org",
                                  "adminFullName": "Ava Smith",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated());
    }

    private void requestReset(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s"}
                                """.formatted(email)))
                .andExpect(status().isNoContent());
    }

    private MvcResult resetPassword(String token, String newPassword, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "newPassword": "%s"}
                                """.formatted(token, newPassword)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private JsonNode login(String email, String password, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void fullResetFlowChangesPasswordAndRevokesSessions() throws Exception {
        String email = uniqueEmail();
        registerOrganization(email);
        JsonNode loginBody = login(email, "password123", 200);
        String oldRefreshToken = loginBody.path("refreshToken").asText();

        requestReset(email);
        assertThat(LAST_RECIPIENT.get()).isEqualTo(email.toLowerCase());
        String token = LAST_TOKEN.get();
        assertThat(token).isNotBlank();

        resetPassword(token, "newPassword456", 204);

        // Old password dead, new password works.
        login(email, "password123", 401);
        login(email, "newPassword456", 200);

        // Pre-reset refresh tokens were revoked.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(oldRefreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenIsSingleUse() throws Exception {
        String email = uniqueEmail();
        registerOrganization(email);
        requestReset(email);
        String token = LAST_TOKEN.get();

        resetPassword(token, "newPassword456", 204);
        resetPassword(token, "anotherPass789", 401);
    }

    @Test
    void newRequestSupersedesOldToken() throws Exception {
        String email = uniqueEmail();
        registerOrganization(email);

        requestReset(email);
        String firstToken = LAST_TOKEN.get();
        requestReset(email);
        String secondToken = LAST_TOKEN.get();
        assertThat(secondToken).isNotEqualTo(firstToken);

        resetPassword(firstToken, "newPassword456", 401);
        resetPassword(secondToken, "newPassword456", 204);
    }

    @Test
    void unknownEmailStillReturns204AndSendsNothing() throws Exception {
        requestReset("nobody-" + uniqueEmail());
        assertThat(LAST_TOKEN.get()).isNull();
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        resetPassword("definitely-not-a-token", "newPassword456", 401);
    }
}
