package com.easytask.backend.tag;

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

/** P4-3 (D32): project-scoped colored task tags — CRUD, task attach/detach, filter, activity. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class TagIntegrationTest extends IntegrationTestSupport {

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

    private String createTag(String token, String projectId, String name, String color) throws Exception {
        return exchange(post("/api/v1/projects/" + projectId + "/tags")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "color": "%s"}
                                """.formatted(name, color)),
                201).path("id").asText();
    }

    private String createTask(String token, String projectId, String title, String assigneeId,
                              List<String> tagIds) throws Exception {
        String tags = tagIds == null ? "null"
                : "[" + String.join(",", tagIds.stream().map(id -> "\"" + id + "\"").toList()) + "]";
        return exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "%s", "assigneeIds": ["%s"], "tagIds": %s}
                                """.formatted(projectId, title, assigneeId, tags)),
                201).path("id").asText();
    }

    @Test
    void tagCrudLifecycleWithPermissionsAndDuplicates() throws Exception {
        Fixture fx = fixture("Tag CRUD Org");

        // manager (task:manage) creates; response shape per contract
        JsonNode created = exchange(post("/api/v1/projects/" + fx.projectId() + "/tags")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Frontend", "color": "#0ea5e9"}
                                """),
                201);
        assertThat(created.path("projectId").asText()).isEqualTo(fx.projectId());
        assertThat(created.path("name").asText()).isEqualTo("Frontend");
        assertThat(created.path("color").asText()).isEqualTo("#0ea5e9");
        String tagId = created.path("id").asText();

        // duplicate name in the same project -> 409
        mockMvc.perform(post("/api/v1/projects/" + fx.projectId() + "/tags")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Frontend", "color": "#22c55e"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // employee lacks task:manage -> 403
        mockMvc.perform(post("/api/v1/projects/" + fx.projectId() + "/tags")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Backend", "color": "#ef4444"}
                                """))
                .andExpect(status().isForbidden());

        // invalid color -> 400
        mockMvc.perform(post("/api/v1/projects/" + fx.projectId() + "/tags")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Bad", "color": "red"}
                                """))
                .andExpect(status().isBadRequest());

        // any project user can list; sorted by name
        createTag(fx.managerToken(), fx.projectId(), "Api", "#ef4444");
        mockMvc.perform(get("/api/v1/projects/" + fx.projectId() + "/tags")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Api"))
                .andExpect(jsonPath("$.items[1].name").value("Frontend"));

        // patch name + color
        mockMvc.perform(patch("/api/v1/tags/" + tagId)
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Front-end", "color": "#22c55e"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Front-end"))
                .andExpect(jsonPath("$.color").value("#22c55e"));

        // renaming onto an existing name -> 409
        mockMvc.perform(patch("/api/v1/tags/" + tagId)
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Api"}
                                """))
                .andExpect(status().isConflict());

        // delete -> gone from the list
        mockMvc.perform(delete("/api/v1/tags/" + tagId)
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/projects/" + fx.projectId() + "/tags")
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Api"));
    }

    @Test
    void taggingTasksListDetailFilterAndActivity() throws Exception {
        Fixture fx = fixture("Tag Task Org");
        String urgentTag = createTag(fx.managerToken(), fx.projectId(), "Urgent", "#ef4444");
        String uiTag = createTag(fx.managerToken(), fx.projectId(), "UI", "#0ea5e9");

        String taggedTask = createTask(fx.managerToken(), fx.projectId(), "Tagged task",
                fx.employeeId(), List.of(urgentTag, uiTag));
        String plainTask = createTask(fx.managerToken(), fx.projectId(), "Plain task",
                fx.employeeId(), null);

        // detail carries tags (sorted by name)
        mockMvc.perform(get("/api/v1/tasks/" + taggedTask)
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0].name").value("UI"))
                .andExpect(jsonPath("$.tags[0].color").value("#0ea5e9"))
                .andExpect(jsonPath("$.tags[1].name").value("Urgent"));

        // list items carry tags; untagged task has an empty array
        JsonNode list = exchange(get("/api/v1/tasks?projectId=" + fx.projectId())
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        for (JsonNode item : list.path("items")) {
            int expected = item.path("id").asText().equals(taggedTask) ? 2 : 0;
            assertThat(item.path("tags").size()).isEqualTo(expected);
        }

        // tagId filter returns only tagged tasks
        mockMvc.perform(get("/api/v1/tasks?tagId=" + urgentTag)
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(taggedTask));
        mockMvc.perform(get("/api/v1/tasks?tagId=" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));

        // update swaps Urgent for nothing, keeps UI -> TAG_REMOVED
        mockMvc.perform(patch("/api/v1/tasks/" + taggedTask)
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tagIds": ["%s"]}
                                """.formatted(uiTag)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0].name").value("UI"));

        // update the plain task to gain a tag -> TAG_ADDED
        mockMvc.perform(patch("/api/v1/tasks/" + plainTask)
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tagIds": ["%s"]}
                                """.formatted(urgentTag)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0].name").value("Urgent"));

        // activity trail: TAG_ADDED on create (newValue = name), TAG_REMOVED on detach (oldValue = name)
        JsonNode activity = exchange(get("/api/v1/tasks/" + taggedTask + "/activity")
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        List<String> added = activity.path("items").valueStream()
                .filter(item -> item.path("eventType").asText().equals("TAG_ADDED"))
                .map(item -> item.path("newValue").asText())
                .toList();
        assertThat(added).containsExactlyInAnyOrder("Urgent", "UI");
        JsonNode removed = activity.path("items").valueStream()
                .filter(item -> item.path("eventType").asText().equals("TAG_REMOVED"))
                .findFirst().orElseThrow();
        assertThat(removed.path("oldValue").asText()).isEqualTo("Urgent");
        assertThat(removed.path("newValue").isNull()).isTrue();
    }

    @Test
    void crossProjectTagIdsAreRejected() throws Exception {
        Fixture fx = fixture("Tag Cross Project Org");
        String otherProjectId = exchange(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Other Project"}
                                """),
                201).path("id").asText();
        String foreignTag = createTag(fx.adminToken(), otherProjectId, "Foreign", "#22c55e");

        // create with a tag from another project -> 400
        mockMvc.perform(post("/api/v1/tasks").header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Mismatched", "tagIds": ["%s"]}
                                """.formatted(fx.projectId(), foreignTag)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.tagIds").exists());

        // update with a tag from another project -> 400
        String taskId = createTask(fx.managerToken(), fx.projectId(), "Update target",
                fx.employeeId(), null);
        mockMvc.perform(patch("/api/v1/tasks/" + taskId)
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tagIds": ["%s"]}
                                """.formatted(foreignTag)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void crossOrgAccessReadsAsNotFound() throws Exception {
        Fixture fx = fixture("Tag Org A");
        String tagId = createTag(fx.managerToken(), fx.projectId(), "Private", "#0ea5e9");

        JsonNode orgB = registerOrganization("Tag Org B", uniqueEmail());
        String outsiderToken = orgB.path("accessToken").asText();

        mockMvc.perform(get("/api/v1/projects/" + fx.projectId() + "/tags")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/projects/" + fx.projectId() + "/tags")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Sneaky", "color": "#000000"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/tags/" + tagId)
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Hijacked"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/tags/" + tagId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }
}
