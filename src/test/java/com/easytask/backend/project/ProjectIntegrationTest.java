package com.easytask.backend.project;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import com.easytask.backend.task.Task;
import com.easytask.backend.task.TaskRepository;
import com.easytask.backend.task.TaskStatus;
import com.easytask.backend.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class ProjectIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private AppUserRepository userRepository;

    private static List<String> names(JsonNode listResponse) {
        List<String> values = new ArrayList<>();
        listResponse.path("items").forEach(item -> values.add(item.path("name").asText()));
        return values;
    }

    private String createProject(String token, String name, int expectedStatus) throws Exception {
        return exchange(post("/api/v1/projects").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "description": "d", "status": "ACTIVE",
                                 "startDate": "2026-06-01", "dueDate": "2026-07-30"}
                                """.formatted(name)),
                expectedStatus).path("id").asText();
    }

    private void addProjectMember(String token, String projectId, String userId) throws Exception {
        exchange(post("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%s"}
                                """.formatted(userId)),
                201);
    }

    @Test
    void adminCreatesAndFiltersProjects() throws Exception {
        JsonNode org = registerOrganization("Proj Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();

        createProject(adminToken, "Alpha App", 201);
        String betaId = createProject(adminToken, "Beta Site", 201);

        // status filter + search
        mockMvc.perform(patch("/api/v1/projects/" + betaId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "COMPLETED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        JsonNode all = exchange(get("/api/v1/projects").header("Authorization", "Bearer " + adminToken), 200);
        assertThat(names(all)).containsExactlyInAnyOrder("Alpha App", "Beta Site");

        JsonNode completed = exchange(get("/api/v1/projects?status=COMPLETED")
                .header("Authorization", "Bearer " + adminToken), 200);
        assertThat(names(completed)).containsExactly("Beta Site");

        JsonNode searched = exchange(get("/api/v1/projects?search=alpha")
                .header("Authorization", "Bearer " + adminToken), 200);
        assertThat(names(searched)).containsExactly("Alpha App");

        JsonNode dueFiltered = exchange(get("/api/v1/projects?dueFrom=2026-08-01")
                .header("Authorization", "Bearer " + adminToken), 200);
        assertThat(dueFiltered.path("totalItems").asLong()).isZero();
    }

    @Test
    void invalidDateRangeIsRejected() throws Exception {
        JsonNode org = registerOrganization("Date Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();

        mockMvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Bad Dates", "startDate": "2026-07-30", "dueDate": "2026-06-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.dueDate").exists());
    }

    @Test
    void managerCreatingProjectBecomesManagingMember() throws Exception {
        JsonNode org = registerOrganization("Mgr Proj Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String managerEmail = uniqueEmail();
        createUser(adminToken, "Mona Manager", managerEmail, "MANAGER");
        String managerToken = loginToken(managerEmail, "password123");

        String projectId = createProject(managerToken, "Managers Own", 201);

        // manager sees it, can update it, and is its only member
        JsonNode visible = exchange(get("/api/v1/projects").header("Authorization", "Bearer " + managerToken), 200);
        assertThat(names(visible)).containsExactly("Managers Own");
        mockMvc.perform(patch("/api/v1/projects/" + projectId).header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "updated"}
                                """))
                .andExpect(status().isOk());
        JsonNode members = exchange(get("/api/v1/projects/" + projectId + "/members")
                .header("Authorization", "Bearer " + managerToken), 200);
        assertThat(members.path("items")).hasSize(1);
        assertThat(members.path("items").get(0).path("fullName").asText()).isEqualTo("Mona Manager");
    }

    @Test
    void visibilityFollowsMembershipAndManagementRights() throws Exception {
        JsonNode org = registerOrganization("Vis Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String managerEmail = uniqueEmail();
        String managerId = createUser(adminToken, "Mona Manager", managerEmail, "MANAGER");
        String employeeEmail = uniqueEmail();
        String employeeId = createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");

        String memberProjectId = createProject(adminToken, "Member Project", 201);
        String hiddenProjectId = createProject(adminToken, "Hidden Project", 201);
        addProjectMember(adminToken, memberProjectId, managerId);
        addProjectMember(adminToken, memberProjectId, employeeId);

        String managerToken = loginToken(managerEmail, "password123");
        String employeeToken = loginToken(employeeEmail, "password123");

        // list visibility
        assertThat(names(exchange(get("/api/v1/projects")
                .header("Authorization", "Bearer " + managerToken), 200))).containsExactly("Member Project");
        assertThat(names(exchange(get("/api/v1/projects")
                .header("Authorization", "Bearer " + employeeToken), 200))).containsExactly("Member Project");

        // detail: hidden project -> 404; member project -> 200
        mockMvc.perform(get("/api/v1/projects/" + hiddenProjectId)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/" + memberProjectId)
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk());

        // manager (MANAGER membership role) can update; employee cannot even though member
        mockMvc.perform(patch("/api/v1/projects/" + memberProjectId)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "by manager"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/projects/" + memberProjectId)
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "by employee"}
                                """))
                .andExpect(status().isForbidden());

        // employee cannot create projects at all
        mockMvc.perform(post("/api/v1/projects").header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Employee Project"}
                                """))
                .andExpect(status().isForbidden());

        // duplicate membership -> 409
        mockMvc.perform(post("/api/v1/projects/" + memberProjectId + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%s"}
                                """.formatted(employeeId)))
                .andExpect(status().isConflict());
    }

    @Test
    void detailReportsTaskSummaryAndProgress() throws Exception {
        JsonNode org = registerOrganization("Progress Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String adminId = org.path("user").path("id").asText();
        String projectId = createProject(adminToken, "Progress Project", 201);

        // seed tasks directly through the repository (task endpoints arrive in M7)
        var project = projectRepository.findById(UUID.fromString(projectId)).orElseThrow();
        var admin = userRepository.findById(UUID.fromString(adminId)).orElseThrow();
        for (TaskStatus status : List.of(TaskStatus.APPROVED, TaskStatus.APPROVED, TaskStatus.TO_DO,
                TaskStatus.IN_PROGRESS, TaskStatus.CANCELLED)) {
            taskRepository.save(Task.builder()
                    .project(project)
                    .createdBy(admin)
                    .title("Task " + status)
                    .status(status)
                    .build());
        }

        // 2 approved / 4 non-cancelled = 50%
        mockMvc.perform(get("/api/v1/projects/" + projectId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercent").value(50))
                .andExpect(jsonPath("$.taskSummary.approved").value(2))
                .andExpect(jsonPath("$.taskSummary.toDo").value(1))
                .andExpect(jsonPath("$.taskSummary.inProgress").value(1))
                .andExpect(jsonPath("$.taskSummary.cancelled").value(1))
                .andExpect(jsonPath("$.taskSummary.inReview").value(0));
    }

    @Test
    void crossOrganizationProjectAccessReturns404() throws Exception {
        JsonNode orgA = registerOrganization("Proj Org A", uniqueEmail());
        String adminA = orgA.path("accessToken").asText();
        String projectAId = createProject(adminA, "A Project", 201);

        JsonNode orgB = registerOrganization("Proj Org B", uniqueEmail());
        String adminB = orgB.path("accessToken").asText();

        mockMvc.perform(get("/api/v1/projects/" + projectAId).header("Authorization", "Bearer " + adminB))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/projects/" + projectAId).header("Authorization", "Bearer " + adminB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Hijacked"}
                                """))
                .andExpect(status().isNotFound());
        assertThat(names(exchange(get("/api/v1/projects").header("Authorization", "Bearer " + adminB), 200)))
                .doesNotContain("A Project");
    }
}
