package com.easytask.backend.summary;

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

/** P4-9 (D38): personal weekly summary bucketing — completed / overdue / upcoming. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class WeeklySummaryIntegrationTest extends IntegrationTestSupport {

    private record Fixture(String adminToken, String employeeToken, String employeeEmail,
                           String employeeId, String projectId) {
    }

    private Fixture fixture() throws Exception {
        JsonNode org = registerOrganization("WS Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String employeeEmail = uniqueEmail();
        String employeeId = createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");
        String projectId = exchange(post("/api/v1/projects").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "WS Project"}
                                """),
                201).path("id").asText();
        exchange(post("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%s"}
                                """.formatted(employeeId)),
                201);
        return new Fixture(adminToken, loginToken(employeeEmail, "password123"), employeeEmail,
                employeeId, projectId);
    }

    private String createTask(Fixture fx, String title, LocalDate due) throws Exception {
        String dueJson = due == null ? "null" : "\"" + due + "\"";
        return exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "%s", "assigneeIds": ["%s"], "dueDate": %s}
                                """.formatted(fx.projectId(), title, fx.employeeId(), dueJson)),
                201).path("id").asText();
    }

    private void transition(Fixture fx, String taskId, String token, String status) throws Exception {
        exchange(patch("/api/v1/tasks/" + taskId + "/status").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "%s"}
                                """.formatted(status)),
                200);
    }

    @Test
    void bucketsCompletedOverdueUpcoming() throws Exception {
        Fixture fx = fixture();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);

        // completed: approved this week (TASK_APPROVED activity within the current ISO week)
        String doneTask = createTask(fx, "Finished feature", today.plusDays(1));
        transition(fx, doneTask, fx.employeeToken(), "IN_PROGRESS");
        transition(fx, doneTask, fx.employeeToken(), "IN_REVIEW");
        transition(fx, doneTask, fx.adminToken(), "APPROVED");

        // overdue: due in the past, still open
        createTask(fx, "Late task", today.minusDays(3));

        // upcoming: due within the current week (on/after today, on/before weekEnd)
        createTask(fx, "Soon task", today);

        // a far-future task should NOT appear in any bucket for the current week
        createTask(fx, "Way later", today.plusDays(60));

        JsonNode summary = exchange(get("/api/v1/me/weekly-summary")
                .header("Authorization", "Bearer " + fx.employeeToken()), 200);

        assertThat(summary.path("weekStart").asText()).isNotBlank();
        assertThat(summary.path("weekEnd").asText()).isNotBlank();

        List<String> completed = titles(summary.path("completed"));
        List<String> overdue = titles(summary.path("overdue"));
        List<String> upcoming = titles(summary.path("upcoming"));

        assertThat(completed).contains("Finished feature");
        assertThat(overdue).contains("Late task").doesNotContain("Finished feature");
        assertThat(upcoming).contains("Soon task").doesNotContain("Way later");
        // approved task is not overdue/upcoming (closed)
        assertThat(overdue).doesNotContain("Finished feature");
        assertThat(upcoming).doesNotContain("Finished feature");
    }

    @Test
    void snapsNonMondayWeekStartToMonday() throws Exception {
        Fixture fx = fixture();
        // 2026-08-19 is a Wednesday; its ISO-week Monday is 2026-08-17
        mockMvc.perform(get("/api/v1/me/weekly-summary?weekStart=2026-08-19")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekStart").value("2026-08-17"))
                .andExpect(jsonPath("$.weekEnd").value("2026-08-23"));
    }

    private List<String> titles(JsonNode array) {
        return array.valueStream().map(n -> n.path("title").asText()).toList();
    }
}
