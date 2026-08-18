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
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P4-10 (D41): recurrence exceptions — occurrences list, skip removes the instance, schedule advances, 409s. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class RecurrenceExceptionIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RecurringTaskGenerationService generationService;

    @Autowired
    private RecurringTaskRuleRepository ruleRepository;

    private record Fixture(String adminToken, String employeeToken, String employeeId, String projectId) {
    }

    private Fixture fixture(String orgName) throws Exception {
        JsonNode org = registerOrganization(orgName, uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String employeeEmail = uniqueEmail();
        String employeeId = createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");
        String projectId = exchange(post("/api/v1/projects").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s Project"}
                                """.formatted(orgName)),
                201).path("id").asText();
        return new Fixture(adminToken, loginToken(employeeEmail, "password123"), employeeId, projectId);
    }

    private String createRule(Fixture fx, LocalDate start, String frequency) throws Exception {
        return exchange(post("/api/v1/recurring-task-rules")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Daily notes", "frequency": "%s",
                                 "interval": 1, "assigneeIds": ["%s"], "recurrenceStartDate": "%s"}
                                """.formatted(fx.projectId(), frequency, fx.employeeId(), start)),
                201).path("id").asText();
    }

    @Test
    void occurrencesListAndSkipFlag() throws Exception {
        Fixture fx = fixture("Occ Org");
        LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        String ruleId = createRule(fx, start, "DAILY");

        JsonNode occ = exchange(get("/api/v1/recurring-task-rules/" + ruleId + "/occurrences?count=4")
                .header("Authorization", "Bearer " + fx.adminToken()), 200);
        assertThat(occ.path("items").size()).isEqualTo(4);
        assertThat(occ.path("items").get(0).path("date").asText()).isEqualTo(start.toString());
        assertThat(occ.path("items").get(0).path("skipped").asBoolean()).isFalse();

        // skip the second occurrence
        LocalDate skipDate = start.plusDays(1);
        mockMvc.perform(post("/api/v1/recurring-task-rules/" + ruleId + "/exceptions")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "%s"}
                                """.formatted(skipDate)))
                .andExpect(status().isNoContent());

        occ = exchange(get("/api/v1/recurring-task-rules/" + ruleId + "/occurrences?count=4")
                .header("Authorization", "Bearer " + fx.adminToken()), 200);
        assertThat(occ.path("items").get(1).path("date").asText()).isEqualTo(skipDate.toString());
        assertThat(occ.path("items").get(1).path("skipped").asBoolean()).isTrue();

        // restore
        mockMvc.perform(delete("/api/v1/recurring-task-rules/" + ruleId + "/exceptions/" + skipDate)
                        .header("Authorization", "Bearer " + fx.adminToken()))
                .andExpect(status().isNoContent());
        occ = exchange(get("/api/v1/recurring-task-rules/" + ruleId + "/occurrences?count=4")
                .header("Authorization", "Bearer " + fx.adminToken()), 200);
        assertThat(occ.path("items").get(1).path("skipped").asBoolean()).isFalse();
    }

    @Test
    void skippedDateProducesNoTaskButScheduleAdvances() throws Exception {
        Fixture fx = fixture("Skip Gen Org");
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);

        // Daily rule due since yesterday: run dates = yesterday(UTC), today(UTC).
        String ruleId = createRule(fx, yesterday, "DAILY");

        // Skip today's not-yet-generated occurrence.
        mockMvc.perform(post("/api/v1/recurring-task-rules/" + ruleId + "/exceptions")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "%s"}
                                """.formatted(todayUtc)))
                .andExpect(status().isNoContent());

        generationService.generateDueTasks();

        // yesterday generated, today skipped -> exactly ONE task.
        JsonNode tasks = exchange(get("/api/v1/recurring-task-rules/" + ruleId + "/tasks")
                .header("Authorization", "Bearer " + fx.adminToken()), 200);
        assertThat(tasks.path("totalItems").asLong()).isEqualTo(1);

        // Schedule still advanced past today; rule stays alive (AF-11).
        var rule = ruleRepository.findById(UUID.fromString(ruleId)).orElseThrow();
        assertThat(rule.isActive()).isTrue();
        assertThat(rule.getNextRunAt().atZone(ZoneOffset.UTC).toLocalDate()).isAfter(todayUtc);
    }

    @Test
    void pastDateOrNonOccurrenceIsConflict() throws Exception {
        Fixture fx = fixture("Excn 409 Org");
        LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        String ruleId = createRule(fx, start, "WEEKLY");

        // past date -> 409
        mockMvc.perform(post("/api/v1/recurring-task-rules/" + ruleId + "/exceptions")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "%s"}
                                """.formatted(LocalDate.now(ZoneOffset.UTC).minusDays(5))))
                .andExpect(status().isConflict());

        // a future date that is NOT on the weekly schedule -> 409
        mockMvc.perform(post("/api/v1/recurring-task-rules/" + ruleId + "/exceptions")
                        .header("Authorization", "Bearer " + fx.adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "%s"}
                                """.formatted(start.plusDays(1))))
                .andExpect(status().isConflict());
    }

    @Test
    void permissionAndOrgIsolation() throws Exception {
        Fixture fx = fixture("Excn Iso Org");
        LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        String ruleId = createRule(fx, start, "DAILY");

        // employee lacks recurring:manage
        mockMvc.perform(get("/api/v1/recurring-task-rules/" + ruleId + "/occurrences")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isForbidden());

        // cross-org admin -> 404
        JsonNode orgB = registerOrganization("Excn Org B", uniqueEmail());
        mockMvc.perform(get("/api/v1/recurring-task-rules/" + ruleId + "/occurrences")
                        .header("Authorization", "Bearer " + orgB.path("accessToken").asText()))
                .andExpect(status().isNotFound());
    }
}
