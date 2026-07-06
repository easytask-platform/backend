package com.easytask.backend.dashboard;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M10: notifications, dashboards, reports. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class DashboardIntegrationTest extends IntegrationTestSupport {

    private record Fixture(String adminToken, String managerToken, String employeeToken,
                           String managerId, String employeeId, String projectId, String taskId) {
    }

    /**
     * Org with one managed project, one task assigned to the employee (due yesterday → overdue),
     * and 2h logged by the employee.
     */
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
        String managerToken = loginToken(managerEmail, "password123");
        String employeeToken = loginToken(employeeEmail, "password123");
        String taskId = exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Overdue work", "estimatedHours": 8,
                                 "startDate": "%s", "dueDate": "%s", "assigneeIds": ["%s"]}
                                """.formatted(projectId, LocalDate.now().minusDays(5),
                                LocalDate.now().minusDays(1), employeeId)),
                201).path("id").asText();
        exchange(post("/api/v1/tasks/" + taskId + "/time-entries")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workDate": "%s", "hoursSpent": 2}
                                """.formatted(LocalDate.now().minusDays(1))),
                201);
        return new Fixture(adminToken, managerToken, employeeToken, managerId, employeeId, projectId, taskId);
    }

    @Test
    void notificationFlow() throws Exception {
        Fixture fx = fixture("Notif Org");

        // employee has a TASK_ASSIGNED notification from the fixture
        JsonNode list = exchange(get("/api/v1/notifications")
                .header("Authorization", "Bearer " + fx.employeeToken()), 200);
        assertThat(list.path("totalItems").asLong()).isEqualTo(1);
        JsonNode notification = list.path("items").get(0);
        assertThat(notification.path("read").asBoolean()).isFalse();
        assertThat(notification.path("relatedTaskId").asText()).isEqualTo(fx.taskId());

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        // another user cannot mark it read
        mockMvc.perform(patch("/api/v1/notifications/" + notification.path("id").asText() + "/read")
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isNotFound());

        // owner marks read; unread filter + count update
        mockMvc.perform(patch("/api/v1/notifications/" + notification.path("id").asText() + "/read")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isNoContent());
        assertThat(exchange(get("/api/v1/notifications?read=false")
                .header("Authorization", "Bearer " + fx.employeeToken()), 200)
                .path("totalItems").asLong()).isZero();

        // read-all works (generate another notification first via a comment from the manager)
        exchange(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "ping"}
                                """),
                201);
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(jsonPath("$.count").value(1));
        mockMvc.perform(patch("/api/v1/notifications/read-all")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void adminDashboardAggregates() throws Exception {
        Fixture fx = fixture("AdminDash Org");

        mockMvc.perform(get("/api/v1/dashboard/admin").header("Authorization", "Bearer " + fx.adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(3))
                .andExpect(jsonPath("$.activeUsers").value(3))
                .andExpect(jsonPath("$.teamCount").value(0))
                .andExpect(jsonPath("$.projectCount").value(1))
                .andExpect(jsonPath("$.taskCount").value(1))
                .andExpect(jsonPath("$.tasksByStatus.toDo").value(1))
                .andExpect(jsonPath("$.overdueTasks.length()").value(1))
                .andExpect(jsonPath("$.overdueTasks[0].title").value("Overdue work"));

        // manager/employee are forbidden
        mockMvc.perform(get("/api/v1/dashboard/admin").header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/dashboard/admin").header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerDashboardScopedToManagedProjects() throws Exception {
        Fixture fx = fixture("MgrDash Org");
        // a second project the manager does not manage, with its own task
        String hiddenProjectId = exchange(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Hidden Project"}
                                """),
                201).path("id").asText();
        exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Hidden task"}
                                """.formatted(hiddenProjectId)),
                201);

        // manager sees only the managed project's task
        mockMvc.perform(get("/api/v1/dashboard/manager").header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managedTaskCount").value(1))
                .andExpect(jsonPath("$.tasksAwaitingReview").value(0))
                .andExpect(jsonPath("$.overdueTaskCount").value(1))
                .andExpect(jsonPath("$.projectProgress.length()").value(1))
                .andExpect(jsonPath("$.projectProgress[0].projectName").value("MgrDash Org Project"));

        // admin variant covers the whole org
        mockMvc.perform(get("/api/v1/dashboard/manager").header("Authorization", "Bearer " + fx.adminToken()))
                .andExpect(jsonPath("$.managedTaskCount").value(2));

        mockMvc.perform(get("/api/v1/dashboard/manager").header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void workloadReportComputesPerUserStats() throws Exception {
        Fixture fx = fixture("Workload Org");

        JsonNode report = exchange(get("/api/v1/reports/workload")
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        JsonNode samRow = report.path("items").valueStream()
                .filter(item -> item.path("fullName").asText().equals("Sam Employee"))
                .findFirst().orElseThrow();
        assertThat(samRow.path("assignedTaskCount").asLong()).isEqualTo(1);
        assertThat(samRow.path("overdueTaskCount").asLong()).isEqualTo(1);
        assertThat(samRow.path("inProgressTaskCount").asLong()).isZero();
        assertThat(samRow.path("loggedHours").asDouble()).isEqualTo(2.0);

        // date window excluding the logged work zeroes loggedHours
        JsonNode windowed = exchange(get("/api/v1/reports/workload?from=%s&to=%s"
                        .formatted(LocalDate.now().plusDays(1), LocalDate.now().plusDays(2)))
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        JsonNode samWindowed = windowed.path("items").valueStream()
                .filter(item -> item.path("fullName").asText().equals("Sam Employee"))
                .findFirst().orElseThrow();
        assertThat(samWindowed.path("loggedHours").asDouble()).isZero();

        mockMvc.perform(get("/api/v1/reports/workload").header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void projectProgressReportAggregates() throws Exception {
        Fixture fx = fixture("Progress Org");
        // add an approved task so progress is 50% (1 approved / 2 non-cancelled)
        String secondTaskId = exchange(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Done work", "estimatedHours": 4,
                                 "assigneeIds": ["%s"]}
                                """.formatted(fx.projectId(), fx.employeeId())),
                201).path("id").asText();
        for (String status : List.of("IN_PROGRESS", "IN_REVIEW")) {
            exchange(patch("/api/v1/tasks/" + secondTaskId + "/status")
                            .header("Authorization", "Bearer " + fx.employeeToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "%s"}
                                    """.formatted(status)),
                    200);
        }
        exchange(patch("/api/v1/tasks/" + secondTaskId + "/status")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "APPROVED"}
                                """),
                200);

        mockMvc.perform(get("/api/v1/reports/project-progress")
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].taskCount").value(2))
                .andExpect(jsonPath("$.items[0].approvedTaskCount").value(1))
                .andExpect(jsonPath("$.items[0].progressPercent").value(50))
                .andExpect(jsonPath("$.items[0].overdueTaskCount").value(1))
                .andExpect(jsonPath("$.items[0].estimatedHours").value(12))
                .andExpect(jsonPath("$.items[0].loggedHours").value(2));

        // projectId outside manager scope -> 404
        String hiddenProjectId = exchange(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Not Mine"}
                                """),
                201).path("id").asText();
        mockMvc.perform(get("/api/v1/reports/project-progress?projectId=" + hiddenProjectId)
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isNotFound());
    }
}
