package com.easytask.backend.task;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P4-6 (D37): blocked/waiting flag — toggle rules, activity, notifications, list/detail exposure. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class TaskBlockedIntegrationTest extends IntegrationTestSupport {

    private record Fixture(String adminToken, String managerToken, String employeeToken,
                           String bystanderToken, String employeeId, String projectId, String taskId) {
    }

    /** Manager-created task assigned to employee; bystander is a project member without assignment. */
    private Fixture fixture(String orgName) throws Exception {
        JsonNode org = registerOrganization(orgName, uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String managerEmail = uniqueEmail();
        String managerId = createUser(adminToken, "Mona Manager", managerEmail, "MANAGER");
        String employeeEmail = uniqueEmail();
        String employeeId = createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");
        String bystanderEmail = uniqueEmail();
        String bystanderId = createUser(adminToken, "Bella Bystander", bystanderEmail, "EMPLOYEE");

        String projectId = exchange(post("/api/v1/projects").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s Project"}
                                """.formatted(orgName)),
                201).path("id").asText();
        for (String userId : List.of(managerId, employeeId, bystanderId)) {
            exchange(post("/api/v1/projects/" + projectId + "/members")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId": "%s"}
                                    """.formatted(userId)),
                    201);
        }
        String managerToken = loginToken(managerEmail, "password123");
        String taskId = exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Blockable task", "assigneeIds": ["%s"]}
                                """.formatted(projectId, employeeId)),
                201).path("id").asText();
        return new Fixture(adminToken, managerToken, loginToken(employeeEmail, "password123"),
                loginToken(bystanderEmail, "password123"), employeeId, projectId, taskId);
    }

    private JsonNode setBlocked(String token, String taskId, boolean blocked, String reason,
                                int expectedStatus) throws Exception {
        String body = reason == null
                ? "{\"blocked\": %s}".formatted(blocked)
                : "{\"blocked\": %s, \"reason\": \"%s\"}".formatted(blocked, reason);
        return exchange(patch("/api/v1/tasks/" + taskId + "/blocked")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                expectedStatus);
    }

    @Test
    void blockUnblockLifecycleWithActivityAndNotifications() throws Exception {
        Fixture fx = fixture("Blocked Org");

        // assignee blocks; status machine untouched
        JsonNode blocked = setBlocked(fx.employeeToken(), fx.taskId(), true, "Waiting for the vendor API key", 200);
        assertThat(blocked.path("blocked").asBoolean()).isTrue();
        assertThat(blocked.path("blockedReason").asText()).isEqualTo("Waiting for the vendor API key");
        assertThat(blocked.path("status").asText()).isEqualTo("TO_DO");

        // flag + reason surface on the list item
        JsonNode list = exchange(get("/api/v1/tasks?projectId=" + fx.projectId())
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        JsonNode item = list.path("items").valueStream()
                .filter(t -> t.path("id").asText().equals(fx.taskId()))
                .findFirst().orElseThrow();
        assertThat(item.path("blocked").asBoolean()).isTrue();
        assertThat(item.path("blockedReason").asText()).isEqualTo("Waiting for the vendor API key");

        // creator (manager) is notified; the actor is not
        JsonNode managerNotifications = exchange(get("/api/v1/notifications")
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        assertThat(managerNotifications.path("items").valueStream()
                .map(n -> n.path("message").asText())
                .filter(m -> m.contains("was blocked"))
                .toList())
                .containsExactly("Task 'Blockable task' was blocked: Waiting for the vendor API key");
        JsonNode employeeNotifications = exchange(get("/api/v1/notifications")
                .header("Authorization", "Bearer " + fx.employeeToken()), 200);
        assertThat(employeeNotifications.path("items").valueStream()
                .map(n -> n.path("message").asText())
                .filter(m -> m.contains("was blocked"))
                .toList()).isEmpty();

        // blocking an already-blocked task just updates the reason
        JsonNode reblocked = setBlocked(fx.managerToken(), fx.taskId(), true, "Key arrived, now waiting for QA", 200);
        assertThat(reblocked.path("blockedReason").asText()).isEqualTo("Key arrived, now waiting for QA");

        // manager's re-block notifies the assignee (not the acting manager)
        JsonNode employeeAfter = exchange(get("/api/v1/notifications")
                .header("Authorization", "Bearer " + fx.employeeToken()), 200);
        assertThat(employeeAfter.path("items").valueStream()
                .map(n -> n.path("message").asText())
                .filter(m -> m.contains("was blocked"))
                .toList())
                .containsExactly("Task 'Blockable task' was blocked: Key arrived, now waiting for QA");

        // unblock clears the reason and notifies nobody
        JsonNode unblocked = setBlocked(fx.employeeToken(), fx.taskId(), false, null, 200);
        assertThat(unblocked.path("blocked").asBoolean()).isFalse();
        assertThat(unblocked.path("blockedReason").isNull()).isTrue();

        // unblocking an unblocked task is a no-op
        setBlocked(fx.employeeToken(), fx.taskId(), false, null, 200);

        // activity trail: two TASK_BLOCKED (newValue = reason), one TASK_UNBLOCKED (oldValue = reason)
        JsonNode activity = exchange(get("/api/v1/tasks/" + fx.taskId() + "/activity")
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        List<String> blockedReasons = activity.path("items").valueStream()
                .filter(e -> e.path("eventType").asText().equals("TASK_BLOCKED"))
                .map(e -> e.path("newValue").asText())
                .toList();
        assertThat(blockedReasons).containsExactlyInAnyOrder(
                "Waiting for the vendor API key", "Key arrived, now waiting for QA");
        List<JsonNode> unblockedEvents = activity.path("items").valueStream()
                .filter(e -> e.path("eventType").asText().equals("TASK_UNBLOCKED"))
                .toList();
        assertThat(unblockedEvents).hasSize(1);
        assertThat(unblockedEvents.getFirst().path("oldValue").asText())
                .isEqualTo("Key arrived, now waiting for QA");

        // total notifications about blocking: exactly the two asserted above (none for unblock)
        JsonNode managerAfter = exchange(get("/api/v1/notifications")
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        assertThat(managerAfter.path("items").valueStream()
                .map(n -> n.path("message").asText())
                .filter(m -> m.contains("was blocked") || m.contains("unblocked"))
                .toList()).hasSize(1);
    }

    @Test
    void blockingRequiresReasonAndProperRole() throws Exception {
        Fixture fx = fixture("Blocked Rules Org");

        // reason required when blocking: missing and blank -> 400 VALIDATION_ERROR on `reason`
        mockMvc.perform(patch("/api/v1/tasks/" + fx.taskId() + "/blocked")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"blocked": true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.reason").exists());
        mockMvc.perform(patch("/api/v1/tasks/" + fx.taskId() + "/blocked")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"blocked": true, "reason": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.reason").exists());
        // > 300 chars -> 400
        setBlocked(fx.employeeToken(), fx.taskId(), true, "x".repeat(301), 400);

        // a non-assignee employee (plain project member) may not toggle the flag
        setBlocked(fx.bystanderToken(), fx.taskId(), true, "Not my task", 403);

        // admin (task:manage) may block without being an assignee
        JsonNode adminBlocked = setBlocked(fx.adminToken(), fx.taskId(), true, "Escalated to org admin", 200);
        assertThat(adminBlocked.path("blocked").asBoolean()).isTrue();

        // cross-org access reads as 404
        String outsiderToken = registerOrganization("Blocked Org B", uniqueEmail())
                .path("accessToken").asText();
        setBlocked(outsiderToken, fx.taskId(), true, "Sneaky", 404);
    }
}
