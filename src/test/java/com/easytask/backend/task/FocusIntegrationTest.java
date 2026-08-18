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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P4-7 (D33): daily-focus pins — lifecycle, order, cap, visibility, privacy. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class FocusIntegrationTest extends IntegrationTestSupport {

    private record Fixture(String adminToken, String managerToken, String employeeToken,
                           String employeeId, String projectId) {
    }

    private Fixture fixture(String orgName) throws Exception {
        JsonNode org = registerOrganization(orgName, uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String managerEmail = uniqueEmail();
        String managerId = createUser(adminToken, "Mona Manager", managerEmail, "MANAGER");
        String employeeEmail = uniqueEmail();
        String employeeId = createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");

        String projectId = exchange(post("/api/v1/projects").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s Project"}
                                """.formatted(orgName)),
                201).path("id").asText();
        for (String userId : List.of(managerId, employeeId)) {
            exchange(post("/api/v1/projects/" + projectId + "/members")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId": "%s"}
                                    """.formatted(userId)),
                    201);
        }
        return new Fixture(adminToken, loginToken(managerEmail, "password123"),
                loginToken(employeeEmail, "password123"), employeeId, projectId);
    }

    private String createTask(String token, String projectId, String title, String assigneeId)
            throws Exception {
        String assignees = assigneeId == null ? "[]" : "[\"%s\"]".formatted(assigneeId);
        return exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "%s", "assigneeIds": %s}
                                """.formatted(projectId, title, assignees)),
                201).path("id").asText();
    }

    private List<String> focusIds(String token) throws Exception {
        return exchange(get("/api/v1/me/focus").header("Authorization", "Bearer " + token), 200)
                .path("items").valueStream()
                .map(item -> item.path("id").asText())
                .toList();
    }

    @Test
    void pinLifecycleOrderAndPrivacy() throws Exception {
        Fixture fx = fixture("Focus Org");
        String first = createTask(fx.managerToken(), fx.projectId(), "First pinned", fx.employeeId());
        String second = createTask(fx.managerToken(), fx.projectId(), "Second pinned", fx.employeeId());
        String unpinned = createTask(fx.managerToken(), fx.projectId(), "Never pinned", fx.employeeId());

        // pin two tasks; list returns the standard task shape, newest pin first
        exchange(post("/api/v1/me/focus/" + first)
                .header("Authorization", "Bearer " + fx.employeeToken()), 204);
        exchange(post("/api/v1/me/focus/" + second)
                .header("Authorization", "Bearer " + fx.employeeToken()), 204);
        assertThat(focusIds(fx.employeeToken())).containsExactly(second, first);
        mockMvc.perform(get("/api/v1/me/focus").header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Second pinned"))
                .andExpect(jsonPath("$.items[0].pinned").value(true))
                .andExpect(jsonPath("$.items[0].projectId").value(fx.projectId()));

        // re-pinning is a no-op
        exchange(post("/api/v1/me/focus/" + first)
                .header("Authorization", "Bearer " + fx.employeeToken()), 204);
        assertThat(focusIds(fx.employeeToken())).containsExactly(second, first);

        // pins are private: another user sees an empty focus board and pinned=false
        assertThat(focusIds(fx.managerToken())).isEmpty();
        mockMvc.perform(get("/api/v1/tasks/" + first)
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(jsonPath("$.pinned").value(false));

        // the caller's pinned flag shows on list and detail
        JsonNode list = exchange(get("/api/v1/tasks?projectId=" + fx.projectId())
                .header("Authorization", "Bearer " + fx.employeeToken()), 200);
        for (JsonNode item : list.path("items")) {
            boolean expected = !item.path("id").asText().equals(unpinned);
            assertThat(item.path("pinned").asBoolean()).isEqualTo(expected);
        }
        mockMvc.perform(get("/api/v1/tasks/" + first)
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(jsonPath("$.pinned").value(true));

        // unpin (idempotent) removes it from the board
        exchange(delete("/api/v1/me/focus/" + first)
                .header("Authorization", "Bearer " + fx.employeeToken()), 204);
        exchange(delete("/api/v1/me/focus/" + first)
                .header("Authorization", "Bearer " + fx.employeeToken()), 204);
        assertThat(focusIds(fx.employeeToken())).containsExactly(second);

        // pinning writes no activity on the task
        JsonNode activity = exchange(get("/api/v1/tasks/" + second + "/activity")
                .header("Authorization", "Bearer " + fx.employeeToken()), 200);
        assertThat(activity.path("items").valueStream()
                .map(e -> e.path("eventType").asText())
                .toList()).containsOnly("TASK_CREATED", "ASSIGNEE_ADDED");
    }

    @Test
    void invisibleTasksCannotBePinnedAndDropOffTheBoard() throws Exception {
        Fixture fx = fixture("Focus Visibility Org");

        // a task the employee cannot see (other project, no membership/assignment) -> 404
        String hiddenProject = exchange(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Hidden Project"}
                                """),
                201).path("id").asText();
        String hiddenTask = createTask(fx.adminToken(), hiddenProject, "Hidden task", null);
        exchange(post("/api/v1/me/focus/" + hiddenTask)
                .header("Authorization", "Bearer " + fx.employeeToken()), 404);
        exchange(post("/api/v1/me/focus/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + fx.employeeToken()), 404);

        // cross-org task -> 404
        Fixture other = fixture("Focus Org B");
        String foreignTask = createTask(other.managerToken(), other.projectId(), "Foreign", null);
        exchange(post("/api/v1/me/focus/" + foreignTask)
                .header("Authorization", "Bearer " + fx.employeeToken()), 404);

        // pinned task drops off the board once the pinner loses visibility
        String fadingTask = createTask(fx.managerToken(), fx.projectId(), "Fading task", fx.employeeId());
        exchange(post("/api/v1/me/focus/" + fadingTask)
                .header("Authorization", "Bearer " + fx.employeeToken()), 204);
        assertThat(focusIds(fx.employeeToken())).containsExactly(fadingTask);
        exchange(patch("/api/v1/tasks/" + fadingTask)
                .header("Authorization", "Bearer " + fx.managerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"assigneeIds": []}
                        """), 200);
        exchange(delete("/api/v1/projects/" + fx.projectId() + "/members/" + fx.employeeId())
                .header("Authorization", "Bearer " + fx.adminToken()), 204);
        assertThat(focusIds(fx.employeeToken())).isEmpty();
    }

    @Test
    void pinsAreCappedAtTwenty() throws Exception {
        Fixture fx = fixture("Focus Cap Org");
        String overflowTask = createTask(fx.adminToken(), fx.projectId(), "Task 21", null);
        for (int i = 1; i <= 20; i++) {
            String taskId = createTask(fx.adminToken(), fx.projectId(), "Task " + i, null);
            exchange(post("/api/v1/me/focus/" + taskId)
                    .header("Authorization", "Bearer " + fx.adminToken()), 204);
        }
        mockMvc.perform(post("/api/v1/me/focus/" + overflowTask)
                        .header("Authorization", "Bearer " + fx.adminToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // re-pinning an existing pin still succeeds at the cap (no-op, not a 409)
        List<String> pinnedIds = focusIds(fx.adminToken());
        assertThat(pinnedIds).hasSize(20);
        exchange(post("/api/v1/me/focus/" + pinnedIds.getFirst())
                .header("Authorization", "Bearer " + fx.adminToken()), 204);

        // unpinning one frees a slot
        exchange(delete("/api/v1/me/focus/" + pinnedIds.getFirst())
                .header("Authorization", "Bearer " + fx.adminToken()), 204);
        exchange(post("/api/v1/me/focus/" + overflowTask)
                .header("Authorization", "Bearer " + fx.adminToken()), 204);
        assertThat(focusIds(fx.adminToken())).hasSize(20).contains(overflowTask);
    }
}
