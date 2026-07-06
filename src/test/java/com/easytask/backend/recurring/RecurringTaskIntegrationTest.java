package com.easytask.backend.recurring;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class RecurringTaskIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RecurringTaskGenerationService generationService;

    @Autowired
    private RecurringTaskRuleRepository ruleRepository;

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
        exchange(post("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%s"}
                                """.formatted(managerId)),
                201);
        return new Fixture(adminToken, loginToken(managerEmail, "password123"),
                loginToken(employeeEmail, "password123"), employeeId, projectId);
    }

    private String createRule(Fixture fx, String title, LocalDate start, LocalDate end,
                              String frequency) throws Exception {
        String endJson = end == null ? "null" : "\"" + end + "\"";
        return exchange(post("/api/v1/recurring-task-rules")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "%s", "priority": "MEDIUM",
                                 "estimatedHours": 2, "assigneeIds": ["%s"],
                                 "frequency": "%s", "interval": 1,
                                 "recurrenceStartDate": "%s", "recurrenceEndDate": %s}
                                """.formatted(fx.projectId(), title, fx.employeeId(), frequency, start, endJson)),
                201).path("id").asText();
    }

    @Test
    void createAndListRules() throws Exception {
        Fixture fx = fixture("Rule Org");
        createRule(fx, "Weekly report", LocalDate.now().plusDays(30), null, "WEEKLY");

        // manager sees the rule; frequency filter works; employee is forbidden
        mockMvc.perform(get("/api/v1/recurring-task-rules").header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Weekly report"))
                .andExpect(jsonPath("$.items[0].frequency").value("WEEKLY"))
                .andExpect(jsonPath("$.items[0].interval").value(1))
                .andExpect(jsonPath("$.items[0].assigneeIds.length()").value(1));
        mockMvc.perform(get("/api/v1/recurring-task-rules?frequency=DAILY")
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/v1/recurring-task-rules")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void generationCreatesTasksWithAssignmentsAndAdvancesSchedule() throws Exception {
        Fixture fx = fixture("Gen Org");
        // due since yesterday, daily -> catch-up should create 2 instances (yesterday + today)
        String ruleId = createRule(fx, "Daily standup notes", LocalDate.now().minusDays(1), null, "DAILY");

        generationService.generateDueTasks();

        JsonNode tasks = exchange(get("/api/v1/recurring-task-rules/" + ruleId + "/tasks")
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        assertThat(tasks.path("totalItems").asLong()).isEqualTo(2);
        assertThat(tasks.path("items").get(0).path("title").asText()).isEqualTo("Daily standup notes");
        assertThat(tasks.path("items").get(0).path("assignees").get(0).path("id").asText())
                .isEqualTo(fx.employeeId());

        // next run is in the future; running again generates nothing new
        var rule = ruleRepository.findById(UUID.fromString(ruleId)).orElseThrow();
        assertThat(rule.getNextRunAt()).isAfter(java.time.Instant.now());
        generationService.generateDueTasks();
        assertThat(exchange(get("/api/v1/recurring-task-rules/" + ruleId + "/tasks")
                .header("Authorization", "Bearer " + fx.managerToken()), 200)
                .path("totalItems").asLong()).isEqualTo(2);

        // generated tasks are assigned and visible to the employee like normal tasks
        assertThat(exchange(get("/api/v1/tasks").header("Authorization", "Bearer " + fx.employeeToken()), 200)
                .path("totalItems").asLong()).isEqualTo(2);
    }

    @Test
    void ruleDeactivatesAfterEndDate() throws Exception {
        Fixture fx = fixture("End Org");
        // window fully in the past: 3 days ago .. 2 days ago -> exactly 2 instances, then inactive
        String ruleId = createRule(fx, "Short lived", LocalDate.now().minusDays(3),
                LocalDate.now().minusDays(2), "DAILY");

        generationService.generateDueTasks();

        assertThat(exchange(get("/api/v1/recurring-task-rules/" + ruleId + "/tasks")
                .header("Authorization", "Bearer " + fx.managerToken()), 200)
                .path("totalItems").asLong()).isEqualTo(2);
        var rule = ruleRepository.findById(UUID.fromString(ruleId)).orElseThrow();
        assertThat(rule.isActive()).isFalse();
    }

    @Test
    void ruleCreationIsScopedToManagedProjects() throws Exception {
        Fixture fx = fixture("Scope Rule Org");

        // manager cannot create a rule for a project they do not manage
        String otherProjectId = exchange(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Other Project"}
                                """),
                201).path("id").asText();
        mockMvc.perform(post("/api/v1/recurring-task-rules")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Sneaky rule", "frequency": "DAILY",
                                 "recurrenceStartDate": "%s"}
                                """.formatted(otherProjectId, LocalDate.now())))
                .andExpect(status().isNotFound());

        // invalid recurrence window -> 400
        mockMvc.perform(post("/api/v1/recurring-task-rules")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Bad window", "frequency": "DAILY",
                                 "recurrenceStartDate": "%s", "recurrenceEndDate": "%s"}
                                """.formatted(fx.projectId(), LocalDate.now(), LocalDate.now().minusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.recurrenceEndDate").exists());

        // cross-org admin cannot read the rule's tasks
        String ruleId = createRule(fx, "Isolated rule", LocalDate.now().plusDays(5), null, "DAILY");
        JsonNode orgB = registerOrganization("Rule Org B", uniqueEmail());
        mockMvc.perform(get("/api/v1/recurring-task-rules/" + ruleId + "/tasks")
                        .header("Authorization", "Bearer " + orgB.path("accessToken").asText()))
                .andExpect(status().isNotFound());
    }
}
