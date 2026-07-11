package com.easytask.backend.user;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class AdminResetPasswordIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueEmail() {
        return "areset" + COUNTER.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
    }

    private JsonNode registerOrganization(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register-organization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "Admin Reset Org",
                                  "adminFullName": "Ava Smith",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createEmployee(String adminToken, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Sam Employee",
                                  "email": "%s",
                                  "initialPassword": "password123",
                                  "role": "EMPLOYEE"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
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
    void adminResetsEmployeePassword() throws Exception {
        String adminEmail = uniqueEmail();
        String adminToken =
                registerOrganization(adminEmail).path("accessToken").asText();
        String employeeEmail = uniqueEmail();
        String employeeId = createEmployee(adminToken, employeeEmail);

        mockMvc.perform(patch("/api/v1/users/{id}/password", employeeId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "temporary987"}
                                """))
                .andExpect(status().isNoContent());

        login(employeeEmail, "password123", 401);
        login(employeeEmail, "temporary987", 200);
    }

    @Test
    void employeeCannotResetPasswords() throws Exception {
        String adminEmail = uniqueEmail();
        String adminToken =
                registerOrganization(adminEmail).path("accessToken").asText();
        String employeeEmail = uniqueEmail();
        String employeeId = createEmployee(adminToken, employeeEmail);
        String employeeToken =
                login(employeeEmail, "password123", 200).path("accessToken").asText();

        mockMvc.perform(patch("/api/v1/users/{id}/password", employeeId)
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "hacked12345"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotResetOwnPasswordHere() throws Exception {
        String adminEmail = uniqueEmail();
        JsonNode registration = registerOrganization(adminEmail);
        String adminToken = registration.path("accessToken").asText();
        String adminId = registration.path("user").path("id").asText();

        mockMvc.perform(patch("/api/v1/users/{id}/password", adminId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "selfreset123"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void adminCannotResetUserInAnotherOrganization() throws Exception {
        String adminAToken =
                registerOrganization(uniqueEmail()).path("accessToken").asText();
        String employeeId = createEmployee(adminAToken, uniqueEmail());

        String adminBToken =
                registerOrganization(uniqueEmail()).path("accessToken").asText();

        mockMvc.perform(patch("/api/v1/users/{id}/password", employeeId)
                        .header("Authorization", "Bearer " + adminBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword": "crossorg123"}
                                """))
                .andExpect(status().isNotFound());
    }
}
