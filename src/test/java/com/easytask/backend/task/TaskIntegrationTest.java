package com.easytask.backend.task;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import com.easytask.backend.activity.TaskActivityLogRepository;
import com.easytask.backend.notification.Notification;
import com.easytask.backend.notification.NotificationRepository;
import com.easytask.backend.notification.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
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
class TaskIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TaskActivityLogRepository activityLogRepository;

    private record Fixture(String adminToken, String managerToken, String employeeToken,
                           String managerId, String employeeId, String projectId) {
    }

    /** Org with admin; manager managing a project; employee who is a project member. */
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
                                {"name": "%s Project", "status": "ACTIVE"}
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
                loginToken(employeeEmail, "password123"), managerId, employeeId, projectId);
    }

    private String createTask(Fixture fx, String title, String... assigneeIds) throws Exception {
        StringBuilder assignees = new StringBuilder();
        for (String id : assigneeIds) {
            if (!assignees.isEmpty()) {
                assignees.append(',');
            }
            assignees.append('"').append(id).append('"');
        }
        return exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "%s", "description": "desc",
                                 "priority": "HIGH", "estimatedHours": 6, "assigneeIds": [%s]}
                                """.formatted(fx.projectId(), title, assignees)),
                201).path("id").asText();
    }

    private void changeStatus(String token, String taskId, String status, int expected) throws Exception {
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "%s"}
                                """.formatted(status)))
                .andExpect(status().is(expected));
    }

    private List<Notification> notificationsFor(String userId) {
        return notificationRepository
                .findAllByRecipientId(UUID.fromString(userId), Pageable.unpaged()).getContent();
    }

    @Test
    void createTaskAssignsNotifiesAndLogs() throws Exception {
        Fixture fx = fixture("Create Org");
        String taskId = createTask(fx, "Build login screen", fx.employeeId());

        mockMvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Build login screen"))
                .andExpect(jsonPath("$.status").value("TO_DO"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.projectName").value("Create Org Project"))
                .andExpect(jsonPath("$.totalLoggedHours").value(0))
                .andExpect(jsonPath("$.assignees[0].fullName").value("Sam Employee"))
                .andExpect(jsonPath("$.assignees[0].email").exists());

        // assignee notified, actor (manager) not
        List<Notification> employeeNotifications = notificationsFor(fx.employeeId());
        assertThat(employeeNotifications).hasSize(1);
        assertThat(employeeNotifications.get(0).getType()).isEqualTo(NotificationType.TASK_ASSIGNED);
        assertThat(notificationsFor(fx.managerId())).isEmpty();

        // activity log contains creation + assignment
        var events = activityLogRepository.findAllByTaskIdOrderByCreatedAtDesc(UUID.fromString(taskId)).stream()
                .map(log -> log.getEventType().name())
                .toList();
        assertThat(events).contains("TASK_CREATED", "ASSIGNEE_ADDED");
    }

    @Test
    void employeeLifecycleAndManagerApproval() throws Exception {
        Fixture fx = fixture("Lifecycle Org");
        String taskId = createTask(fx, "Lifecycle task", fx.employeeId());

        // employee walks the happy path
        changeStatus(fx.employeeToken(), taskId, "IN_PROGRESS", 200);
        changeStatus(fx.employeeToken(), taskId, "IN_REVIEW", 200);
        // employee cannot approve
        changeStatus(fx.employeeToken(), taskId, "APPROVED", 409);
        // manager reopens, employee resumes, manager approves
        changeStatus(fx.managerToken(), taskId, "REOPENED", 200);
        changeStatus(fx.employeeToken(), taskId, "IN_REVIEW", 200);
        changeStatus(fx.managerToken(), taskId, "APPROVED", 200);

        mockMvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // approved task's fields are still locked from editing
        mockMvc.perform(patch("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "New title"}
                                """))
                .andExpect(status().isConflict());
        // but a task:manage holder (manager/admin) can now move it back out of APPROVED
        changeStatus(fx.managerToken(), taskId, "CANCELLED", 200);

        // employee received reopened + approved notifications along the way
        var types = notificationsFor(fx.employeeId()).stream().map(n -> n.getType().name()).toList();
        assertThat(types).contains("TASK_ASSIGNED", "TASK_REOPENED", "TASK_APPROVED");
    }

    @Test
    void statusChangePermissionsAreScoped() throws Exception {
        Fixture fx = fixture("Perm Org");
        String taskId = createTask(fx, "Scoped task", fx.employeeId());

        // a second employee in the project cannot move a task not assigned to them
        String otherEmail = uniqueEmail();
        String otherId = createUser(fx.adminToken(), "Olly Other", otherEmail, "EMPLOYEE");
        exchange(post("/api/v1/projects/" + fx.projectId() + "/members")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%s"}
                                """.formatted(otherId)),
                201);
        changeStatus(loginToken(otherEmail, "password123"), taskId, "IN_PROGRESS", 403);

        // invalid transition for assignee -> 409
        changeStatus(fx.employeeToken(), taskId, "IN_REVIEW", 409);

        // admin can cancel any non-approved task
        changeStatus(fx.adminToken(), taskId, "CANCELLED", 200);
    }

    @Test
    void updateDiffsAssigneesAndNotifies() throws Exception {
        Fixture fx = fixture("Diff Org");
        String secondEmail = uniqueEmail();
        String secondId = createUser(fx.adminToken(), "Nina New", secondEmail, "EMPLOYEE");
        String taskId = createTask(fx, "Diff task", fx.employeeId());

        // replace employee with second employee, change priority
        mockMvc.perform(patch("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priority": "CRITICAL", "assigneeIds": ["%s"]}
                                """.formatted(secondId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.assignees.length()").value(1))
                .andExpect(jsonPath("$.assignees[0].fullName").value("Nina New"));

        // new assignee got TASK_ASSIGNED; actor got nothing
        var newAssigneeTypes = notificationsFor(secondId).stream().map(n -> n.getType().name()).toList();
        assertThat(newAssigneeTypes).containsExactly("TASK_ASSIGNED");
        assertThat(notificationsFor(fx.managerId())).isEmpty();

        var events = activityLogRepository.findAllByTaskIdOrderByCreatedAtDesc(UUID.fromString(taskId)).stream()
                .map(log -> log.getEventType().name())
                .toList();
        assertThat(events).contains("ASSIGNEE_REMOVED", "ASSIGNEE_ADDED", "TASK_UPDATED");
    }

    @Test
    void listFiltersWork() throws Exception {
        Fixture fx = fixture("Filter Org");
        String taskA = createTask(fx, "Alpha work item", fx.employeeId());
        createTask(fx, "Beta work item", fx.managerId());

        // overdue task: due yesterday
        exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Overdue item", "priority": "LOW",
                                 "startDate": "2026-01-01", "dueDate": "2026-01-02"}
                                """.formatted(fx.projectId())),
                201);

        String auth = "Bearer " + fx.adminToken();
        assertThat(exchange(get("/api/v1/tasks?search=alpha").header("Authorization", auth), 200)
                .path("totalItems").asLong()).isEqualTo(1);
        assertThat(exchange(get("/api/v1/tasks?priority=LOW").header("Authorization", auth), 200)
                .path("totalItems").asLong()).isEqualTo(1);
        assertThat(exchange(get("/api/v1/tasks?assigneeId=" + fx.employeeId()).header("Authorization", auth), 200)
                .path("totalItems").asLong()).isEqualTo(1);
        assertThat(exchange(get("/api/v1/tasks?overdue=true").header("Authorization", auth), 200)
                .path("totalItems").asLong()).isEqualTo(1);
        assertThat(exchange(get("/api/v1/tasks?overdue=false").header("Authorization", auth), 200)
                .path("totalItems").asLong()).isEqualTo(2);
        assertThat(exchange(get("/api/v1/tasks?status=TO_DO").header("Authorization", auth), 200)
                .path("totalItems").asLong()).isEqualTo(3);

        // employee task moves; status filter follows
        changeStatus(fx.employeeToken(), taskA, "IN_PROGRESS", 200);
        assertThat(exchange(get("/api/v1/tasks?status=IN_PROGRESS").header("Authorization", auth), 200)
                .path("totalItems").asLong()).isEqualTo(1);
    }

    @Test
    void visibilityIsScopedByRoleAndOrganization() throws Exception {
        Fixture fx = fixture("TaskVis Org");
        String taskId = createTask(fx, "Visible task", fx.employeeId());

        // employee not in the project and without assignment sees nothing
        String outsiderEmail = uniqueEmail();
        createUser(fx.adminToken(), "Out Sider", outsiderEmail, "EMPLOYEE");
        String outsiderToken = loginToken(outsiderEmail, "password123");
        assertThat(exchange(get("/api/v1/tasks").header("Authorization", "Bearer " + outsiderToken), 200)
                .path("totalItems").asLong()).isZero();
        mockMvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());

        // employee POST /tasks -> 403
        mockMvc.perform(post("/api/v1/tasks").header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Employee task"}
                                """.formatted(fx.projectId())))
                .andExpect(status().isForbidden());

        // cross-org admin: 404 on detail, empty list
        JsonNode orgB = registerOrganization("TaskVis Org B", uniqueEmail());
        String adminB = orgB.path("accessToken").asText();
        mockMvc.perform(get("/api/v1/tasks/" + taskId).header("Authorization", "Bearer " + adminB))
                .andExpect(status().isNotFound());
        assertThat(exchange(get("/api/v1/tasks").header("Authorization", "Bearer " + adminB), 200)
                .path("totalItems").asLong()).isZero();

        // manager cannot create a task in a project they do not manage
        String lonelyProjectId = exchange(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Unmanaged Project"}
                                """),
                201).path("id").asText();
        mockMvc.perform(post("/api/v1/tasks").header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Sneaky task"}
                                """.formatted(lonelyProjectId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listShapeMatchesContract() throws Exception {
        Fixture fx = fixture("Shape Org");
        createTask(fx, "Shape task", fx.employeeId());

        JsonNode list = exchange(get("/api/v1/tasks").header("Authorization", "Bearer " + fx.adminToken()), 200);
        JsonNode item = list.path("items").get(0);
        List<String> fields = new ArrayList<>();
        item.properties().forEach(e -> fields.add(e.getKey()));
        assertThat(fields).containsExactlyInAnyOrder("id", "projectId", "projectName", "title", "status",
                "priority", "startDate", "dueDate", "estimatedHours", "totalLoggedHours", "overdue", "assignees",
                "tags", "blocked", "blockedReason", "checklistDone", "checklistTotal", "pinned");
        assertThat(item.path("assignees").get(0).path("fullName").asText()).isEqualTo("Sam Employee");
    }
}
