package com.easytask.backend.user;

import com.easytask.backend.TestDatabaseConfiguration;
import com.easytask.backend.auth.PasswordResetMailer;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P3-2 (D24): invitation onboarding + forced first-login password change.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestDatabaseConfiguration.class, InvitationIntegrationTest.CapturingMailerConfig.class})
class InvitationIntegrationTest {

    private static final AtomicReference<String> LAST_INVITE_TOKEN = new AtomicReference<>();
    private static final AtomicReference<String> LAST_INVITE_ORG = new AtomicReference<>();

    @TestConfiguration
    static class CapturingMailerConfig {
        @Bean
        @Primary
        PasswordResetMailer capturingMailer() {
            return new PasswordResetMailer() {
                @Override
                public void sendResetToken(AppUser user, String rawToken) {
                }

                @Override
                public void sendInvitation(AppUser user, String organizationName, String rawToken) {
                    LAST_INVITE_TOKEN.set(rawToken);
                    LAST_INVITE_ORG.set(organizationName);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminEmail;
    private String adminAccessToken;

    @BeforeEach
    void registerOrgAndLoginAdmin() throws Exception {
        LAST_INVITE_TOKEN.set(null);
        LAST_INVITE_ORG.set(null);
        adminEmail = "admin-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "Invite Org",
                                  "adminFullName": "Ava Admin",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(adminEmail)))
                .andExpect(status().isCreated());
        adminAccessToken = login(adminEmail, "password123", 200).path("accessToken").asText();
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

    private void createUser(String email, String initialPasswordJsonFragment) throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Sam Employee",
                                  "email": "%s"%s,
                                  "role": "EMPLOYEE"
                                }
                                """.formatted(email, initialPasswordJsonFragment)))
                .andExpect(status().isCreated());
    }

    @Test
    void userCreatedWithoutPasswordIsInvitedAndSetsTheirOwn() throws Exception {
        String email = "sam-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        createUser(email, "");

        assertThat(LAST_INVITE_ORG.get()).isEqualTo("Invite Org");
        String inviteToken = LAST_INVITE_TOKEN.get();
        assertThat(inviteToken).isNotBlank();

        // Nothing to log in with yet.
        login(email, "password123", 401);

        // Redeeming the invite = the normal reset-password endpoint.
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "newPassword": "myOwnSecret9"}
                                """.formatted(inviteToken)))
                .andExpect(status().isNoContent());

        JsonNode body = login(email, "myOwnSecret9", 200);
        assertThat(body.path("user").path("mustChangePassword").asBoolean()).isFalse();
    }

    @Test
    void adminSetPasswordForcesChangeOnFirstLogin() throws Exception {
        String email = "temp-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        createUser(email, ",\n  \"initialPassword\": \"tempPass123\"");
        assertThat(LAST_INVITE_TOKEN.get()).isNull(); // no invitation in this mode

        JsonNode body = login(email, "tempPass123", 200);
        assertThat(body.path("user").path("mustChangePassword").asBoolean()).isTrue();
        String accessToken = body.path("accessToken").asText();

        // /me also reports the flag so session restores enforce it too.
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // Changing the password clears the flag.
        mockMvc.perform(patch("/api/v1/me/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "tempPass123", "newPassword": "chosenByMe77"}
                                """))
                .andExpect(status().isNoContent());

        JsonNode after = login(email, "chosenByMe77", 200);
        assertThat(after.path("user").path("mustChangePassword").asBoolean()).isFalse();
    }

    @Test
    void adminResetPasswordSetsTheFlagAgain() throws Exception {
        String email = "reset-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        createUser(email, ",\n  \"initialPassword\": \"tempPass123\"");
        String userId = login(email, "tempPass123", 200).path("user").path("id").asText();

        // Clear the flag by choosing a password...
        String accessToken = login(email, "tempPass123", 200).path("accessToken").asText();
        mockMvc.perform(patch("/api/v1/me/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "tempPass123", "newPassword": "chosenByMe77"}
                                """))
                .andExpect(status().isNoContent());

        // ...then the admin resets it: the flag must come back.
        mockMvc.perform(patch("/api/v1/users/" + userId + "/password")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "adminKnows99"}
                                """))
                .andExpect(status().isNoContent());

        JsonNode body = login(email, "adminKnows99", 200);
        assertThat(body.path("user").path("mustChangePassword").asBoolean()).isTrue();
    }
}
