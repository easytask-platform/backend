package com.easytask.backend.role;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class RoleManagementIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueEmail() {
        return "rbac" + COUNTER.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
    }

    private JsonNode registerOrganization(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "RBAC Org %s",
                                  "adminFullName": "Ava Smith",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(email, email)))
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

    private String createUser(String adminToken, String email, String roleField) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Member User",
                                  "email": "%s",
                                  "initialPassword": "password123",
                                  %s
                                }
                                """.formatted(email, roleField)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }

    @Test
    void registrationProvisionsSystemRolesAndPermissionClaims() throws Exception {
        String email = uniqueEmail();
        JsonNode registration = registerOrganization(email);
        JsonNode loginBody = login(email, "password123", 200);

        assertThat(registration.path("user").path("role").asText()).isEqualTo("ORGANIZATION_ADMIN");
        assertThat(loginBody.path("user").path("scope").asText()).isEqualTo("ORGANIZATION");
        assertThat(loginBody.path("user").path("permissions")).isNotEmpty();
        assertThat(loginBody.path("user").path("roleId").asText()).isNotBlank();

        String token = loginBody.path("accessToken").asText();
        MvcResult roles = mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(roles.getResponse().getContentAsString()).path("items");
        assertThat(items).hasSize(3);
        java.util.List<String> names = new java.util.ArrayList<>();
        items.forEach(item -> names.add(item.path("name").asText()));
        assertThat(names).containsExactlyInAnyOrder("ORGANIZATION_ADMIN", "MANAGER", "EMPLOYEE");
    }

    @Test
    void legacyRoleNameStillWorksWhenCreatingUsers() throws Exception {
        String adminEmail = uniqueEmail();
        registerOrganization(adminEmail);
        String token = login(adminEmail, "password123", 200).path("accessToken").asText();

        String employeeEmail = uniqueEmail();
        createUser(token, employeeEmail, "\"role\": \"EMPLOYEE\"");
        JsonNode employeeLogin = login(employeeEmail, "password123", 200);
        assertThat(employeeLogin.path("user").path("role").asText()).isEqualTo("EMPLOYEE");
        assertThat(employeeLogin.path("user").path("scope").asText()).isEqualTo("ASSIGNED");
        assertThat(employeeLogin.path("user").path("permissions").isArray()).isTrue();
        assertThat(employeeLogin.path("user").path("permissions").size()).isEqualTo(1);
    }

    @Test
    void customRoleGrantsExactlyItsPermissions() throws Exception {
        String adminEmail = uniqueEmail();
        registerOrganization(adminEmail);
        String adminToken = login(adminEmail, "password123", 200).path("accessToken").asText();

        // Custom "Reviewer" role: sees managed data, can review but not manage tasks.
        MvcResult created = mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Reviewer",
                                  "dataScope": "MANAGED",
                                  "permissions": ["task:review", "user:read"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.system").value(false))
                .andReturn();
        String roleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("id").asText();

        String reviewerEmail = uniqueEmail();
        createUser(adminToken, reviewerEmail, "\"roleId\": \"%s\"".formatted(roleId));
        String reviewerToken = login(reviewerEmail, "password123", 200).path("accessToken").asText();

        // user:read granted → can list users
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        // task:manage NOT granted → cannot create tasks
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "nope"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        // role:manage NOT granted → cannot see the permission catalog
        mockMvc.perform(get("/api/v1/permissions")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemRolesAreImmutableAndUsedRolesUndeletable() throws Exception {
        String adminEmail = uniqueEmail();
        registerOrganization(adminEmail);
        String adminToken = login(adminEmail, "password123", 200).path("accessToken").asText();

        MvcResult roles = mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(roles.getResponse().getContentAsString()).path("items");
        String adminRoleId = null;
        for (JsonNode item : items) {
            if (item.path("name").asText().equals("ORGANIZATION_ADMIN")) {
                adminRoleId = item.path("id").asText();
            }
        }

        // System role: no edits, no delete.
        mockMvc.perform(patch("/api/v1/roles/{id}", adminRoleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Root"}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/roles/{id}", adminRoleId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());

        // Custom role held by a user: delete blocked until reassigned.
        MvcResult created = mockMvc.perform(post("/api/v1/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Temp", "dataScope": "ASSIGNED", "permissions": ["task:execute"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String tempRoleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("id").asText();
        String holderEmail = uniqueEmail();
        String holderId = createUser(adminToken, holderEmail, "\"roleId\": \"%s\"".formatted(tempRoleId));

        mockMvc.perform(delete("/api/v1/roles/{id}", tempRoleId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());

        // Reassign holder to EMPLOYEE, then delete succeeds.
        mockMvc.perform(patch("/api/v1/users/{id}", holderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "EMPLOYEE"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/roles/{id}", tempRoleId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void roleChangeRevokesRefreshTokens() throws Exception {
        String adminEmail = uniqueEmail();
        registerOrganization(adminEmail);
        String adminToken = login(adminEmail, "password123", 200).path("accessToken").asText();

        String userEmail = uniqueEmail();
        String userId = createUser(adminToken, userEmail, "\"role\": \"EMPLOYEE\"");
        JsonNode userLogin = login(userEmail, "password123", 200);
        String refreshToken = userLogin.path("refreshToken").asText();

        mockMvc.perform(patch("/api/v1/users/{id}", userId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "MANAGER"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));

        // Old refresh token is dead; a fresh login carries the new claims.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
        JsonNode relogin = login(userEmail, "password123", 200);
        assertThat(relogin.path("user").path("scope").asText()).isEqualTo("MANAGED");
    }
}
