package com.easytask.backend.checklist;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P4-5 (D36): per-task checklists — lifecycle, permission matrix, progress counts. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class ChecklistIntegrationTest extends IntegrationTestSupport {

    private record Fixture(String adminToken, String managerToken, String employeeToken,
                           String bystanderToken, String employeeId, String projectId, String taskId) {
    }

    /** Org with a manager-run project; employee is assigned to the task, bystander is only a member. */
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
        for (String userId : java.util.List.of(managerId, employeeId, bystanderId)) {
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
                                {"projectId": "%s", "title": "Checklist target", "assigneeIds": ["%s"]}
                                """.formatted(projectId, employeeId)),
                201).path("id").asText();
        return new Fixture(adminToken, managerToken, loginToken(employeeEmail, "password123"),
                loginToken(bystanderEmail, "password123"), employeeId, projectId, taskId);
    }

    private String addItem(String token, String taskId, String title) throws Exception {
        return exchange(post("/api/v1/tasks/" + taskId + "/checklist")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s"}
                                """.formatted(title)),
                201).path("id").asText();
    }

    @Test
    void checklistLifecycleWithProgressCounts() throws Exception {
        Fixture fx = fixture("Checklist Org");

        // manager appends items at max(position)+1
        JsonNode first = exchange(post("/api/v1/tasks/" + fx.taskId() + "/checklist")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Draft the outline"}
                                """),
                201);
        assertThat(first.path("taskId").asText()).isEqualTo(fx.taskId());
        assertThat(first.path("done").asBoolean()).isFalse();
        assertThat(first.path("position").asInt()).isEqualTo(1);
        String firstId = first.path("id").asText();
        String secondId = addItem(fx.managerToken(), fx.taskId(), "Review with the team");
        String thirdId = addItem(fx.managerToken(), fx.taskId(), "Publish");

        // any task viewer can list; ordered by position
        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId() + "/checklist")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].title").value("Draft the outline"))
                .andExpect(jsonPath("$.items[1].position").value(2))
                .andExpect(jsonPath("$.items[2].title").value("Publish"));

        // assignee ticks their own work
        mockMvc.perform(patch("/api/v1/checklist-items/" + firstId)
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"done": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));

        // manager renames and reorders
        mockMvc.perform(patch("/api/v1/checklist-items/" + secondId)
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Review with design", "position": 5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Review with design"))
                .andExpect(jsonPath("$.position").value(5));

        // progress counts on detail and list
        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId())
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checklistDone").value(1))
                .andExpect(jsonPath("$.checklistTotal").value(3));
        JsonNode list = exchange(get("/api/v1/tasks?projectId=" + fx.projectId())
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        JsonNode item = list.path("items").valueStream()
                .filter(t -> t.path("id").asText().equals(fx.taskId()))
                .findFirst().orElseThrow();
        assertThat(item.path("checklistDone").asInt()).isEqualTo(1);
        assertThat(item.path("checklistTotal").asInt()).isEqualTo(3);

        // delete drops the item from list and counts
        mockMvc.perform(delete("/api/v1/checklist-items/" + thirdId)
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId())
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(jsonPath("$.checklistTotal").value(2));

        // checklist writes produce no activity entries (D36)
        JsonNode activity = exchange(get("/api/v1/tasks/" + fx.taskId() + "/activity")
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        assertThat(activity.path("items").valueStream()
                .map(entry -> entry.path("eventType").asText())
                .toList()).containsOnly("TASK_CREATED", "ASSIGNEE_ADDED");
    }

    @Test
    void permissionMatrixForEmployees() throws Exception {
        Fixture fx = fixture("Checklist Perm Org");
        String itemId = addItem(fx.managerToken(), fx.taskId(), "Only managers restructure");

        // employee-assignee: cannot add, rename, reorder, or delete
        mockMvc.perform(post("/api/v1/tasks/" + fx.taskId() + "/checklist")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Sneaky add"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/checklist-items/" + itemId)
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Renamed by employee"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/checklist-items/" + itemId)
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position": 9}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/checklist-items/" + itemId)
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isForbidden());

        // ...but CAN toggle done (covered positively above); a non-assignee employee cannot
        mockMvc.perform(patch("/api/v1/checklist-items/" + itemId)
                        .header("Authorization", "Bearer " + fx.bystanderToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"done": true}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/checklist-items/" + itemId)
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"done": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));

        // blank title -> 400
        mockMvc.perform(post("/api/v1/tasks/" + fx.taskId() + "/checklist")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "  "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossOrgAccessReadsAsNotFound() throws Exception {
        Fixture fx = fixture("Checklist Org A");
        String itemId = addItem(fx.managerToken(), fx.taskId(), "Private item");

        String outsiderToken = registerOrganization("Checklist Org B", uniqueEmail())
                .path("accessToken").asText();

        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId() + "/checklist")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/tasks/" + fx.taskId() + "/checklist")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Sneaky"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/checklist-items/" + itemId)
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"done": true}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/checklist-items/" + itemId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }
}
