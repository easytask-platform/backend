package com.easytask.backend.activity;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P3-4 (D27): org-wide activity feed with dataScope-based visibility. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class OrgActivityFeedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode postJson(String path, String body, String token, int expected) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) request = request.header("Authorization", "Bearer " + token);
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expected)).andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? objectMapper.nullNode() : objectMapper.readTree(content);
    }

    @Test
    void feedIsScopedByRole() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String adminEmail = "admin-" + suffix + "@example.com";

        postJson("/api/v1/auth/register-organization", """
                {"organizationName": "Feed Org", "adminFullName": "Ava", "email": "%s", "password": "password123"}
                """.formatted(adminEmail), null, 201);
        String adminToken = postJson("/api/v1/auth/login", """
                {"email": "%s", "password": "password123"}
                """.formatted(adminEmail), null, 200).path("accessToken").asText();

        // Unassigned employee for the ASSIGNED-scope check.
        String employeeEmail = "emp-" + suffix + "@example.com";
        postJson("/api/v1/users", """
                {"fullName": "Sam", "email": "%s", "initialPassword": "password123", "role": "EMPLOYEE"}
                """.formatted(employeeEmail), adminToken, 201);
        String employeeToken = postJson("/api/v1/auth/login", """
                {"email": "%s", "password": "password123"}
                """.formatted(employeeEmail), null, 200).path("accessToken").asText();

        String projectId = postJson("/api/v1/projects", """
                {"name": "Feed Project", "description": "", "status": "ACTIVE", "startDate": null, "dueDate": null}
                """, adminToken, 201).path("id").asText();
        postJson("/api/v1/tasks", """
                {"projectId": "%s", "title": "Feed Task", "description": "", "priority": "LOW",
                 "startDate": null, "dueDate": null, "estimatedHours": null, "assigneeIds": []}
                """.formatted(projectId), adminToken, 201);

        // Admin (ORGANIZATION scope) sees the TASK_CREATED event with context.
        MvcResult adminFeed = mockMvc.perform(get("/api/v1/activity")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(adminFeed.getResponse().getContentAsString()).path("items");
        assertThat(items.size()).isGreaterThan(0);
        JsonNode first = items.get(0);
        assertThat(first.path("taskTitle").asText()).isEqualTo("Feed Task");
        assertThat(first.path("projectName").asText()).isEqualTo("Feed Project");
        assertThat(first.path("eventType").asText()).isEqualTo("TASK_CREATED");
        assertThat(first.path("actor").path("fullName").asText()).isEqualTo("Ava");

        // Unassigned employee (ASSIGNED scope) sees nothing.
        MvcResult employeeFeed = mockMvc.perform(get("/api/v1/activity")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(employeeFeed.getResponse().getContentAsString())
                .path("items").size()).isZero();
    }
}
