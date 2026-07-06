package com.easytask.backend.auth;

import com.easytask.backend.TestDatabaseConfiguration;
import com.easytask.backend.user.AppUserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class AuthIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository userRepository;

    private String uniqueEmail() {
        return "user" + COUNTER.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
    }

    private JsonNode registerOrganization(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "Acme Team",
                                  "adminFullName": "Ava Smith",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
    void registerOrganizationReturnsOrgAdminAndTokens() throws Exception {
        String email = uniqueEmail();
        JsonNode body = registerOrganization(email);

        org.assertj.core.api.Assertions.assertThat(body.path("organization").path("name").asText())
                .isEqualTo("Acme Team");
        org.assertj.core.api.Assertions.assertThat(body.path("user").path("role").asText())
                .isEqualTo("ORGANIZATION_ADMIN");
        org.assertj.core.api.Assertions.assertThat(body.path("user").path("email").asText()).isEqualTo(email);
        org.assertj.core.api.Assertions.assertThat(body.path("accessToken").asText()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(body.path("refreshToken").asText()).isNotBlank();

        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + body.path("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.organizationName").value("Acme Team"))
                .andExpect(jsonPath("$.role").value("ORGANIZATION_ADMIN"));
    }

    @Test
    void duplicateEmailRegistrationIsRejectedWithConflict() throws Exception {
        String email = uniqueEmail();
        registerOrganization(email);

        mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "Other Team",
                                  "adminFullName": "Bob Jones",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(email.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void invalidRegistrationBodyReturnsValidationErrorWithFields() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "A",
                                  "adminFullName": "",
                                  "email": "not-an-email",
                                  "password": "short"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.organizationName").exists())
                .andExpect(jsonPath("$.fields.adminFullName").exists())
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    void loginReturnsUserWithOrganizationName() throws Exception {
        String email = uniqueEmail();
        registerOrganization(email);

        JsonNode body = login(email, "password123", 200);
        org.assertj.core.api.Assertions.assertThat(body.path("user").path("organizationName").asText())
                .isEqualTo("Acme Team");
        org.assertj.core.api.Assertions.assertThat(body.path("accessToken").asText()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(body.path("refreshToken").asText()).isNotBlank();
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String email = uniqueEmail();
        registerOrganization(email);

        JsonNode body = login(email, "wrong-password", 401);
        org.assertj.core.api.Assertions.assertThat(body.path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void deactivatedUserCannotLogInAndCannotRefresh() throws Exception {
        String email = uniqueEmail();
        JsonNode registered = registerOrganization(email);
        String refreshToken = registered.path("refreshToken").asText();

        var user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setActive(false);
        userRepository.save(user);

        JsonNode body = login(email, "password123", 403);
        org.assertj.core.api.Assertions.assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void refreshRotatesTokenAndRejectsTheOldOne() throws Exception {
        String email = uniqueEmail();
        JsonNode registered = registerOrganization(email);
        String firstRefreshToken = registered.path("refreshToken").asText();

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(firstRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        JsonNode refreshed = objectMapper.readTree(refreshResult.getResponse().getContentAsString());

        // the rotated-out token is now rejected
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(firstRefreshToken)))
                .andExpect(status().isUnauthorized());

        // the new access token works
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + refreshed.path("accessToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void refreshWithUnknownTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        String email = uniqueEmail();
        JsonNode registered = registerOrganization(email);
        String accessToken = registered.path("accessToken").asText();
        String refreshToken = registered.path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutBearerTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "whatever"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void protectedEndpointWithoutOrWithGarbageTokenReturnsContractError() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer garbage.token.here"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void changePasswordFlowEnforcesCurrentPasswordAndRevokesSessions() throws Exception {
        String email = uniqueEmail();
        JsonNode registered = registerOrganization(email);
        String accessToken = registered.path("accessToken").asText();
        String refreshToken = registered.path("refreshToken").asText();

        // wrong current password -> 403
        mockMvc.perform(patch("/api/v1/me/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "wrong", "newPassword": "newPassword123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // correct current password -> 204
        mockMvc.perform(patch("/api/v1/me/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "password123", "newPassword": "newPassword123"}
                                """))
                .andExpect(status().isNoContent());

        // all refresh tokens revoked
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized());

        // old password no longer works, new one does
        login(email, "password123", 401);
        login(email, "newPassword123", 200);
    }
}
