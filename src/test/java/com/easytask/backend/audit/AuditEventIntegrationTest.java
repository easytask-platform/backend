package com.easytask.backend.audit;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P4-11 (D39): audit events populated by real admin actions via dual-write; audit:read gating; org isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class AuditEventIntegrationTest extends IntegrationTestSupport {

    @Test
    void roleChangeAndDeactivationProduceAuditRows() throws Exception {
        JsonNode org = registerOrganization("Audit Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String targetEmail = uniqueEmail();
        String targetId = createUser(adminToken, "Target User", targetEmail, "EMPLOYEE");

        // real admin action #1: role change -> ROLE_CHANGED
        exchange(patch("/api/v1/users/" + targetId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "MANAGER"}
                                """),
                200);
        // real admin action #2: deactivation -> USER_DEACTIVATED (soft delete = PATCH /deactivate)
        exchange(patch("/api/v1/users/" + targetId + "/deactivate")
                .header("Authorization", "Bearer " + adminToken), 204);

        JsonNode events = exchange(get("/api/v1/audit-events").header("Authorization", "Bearer " + adminToken), 200);
        List<String> types = events.path("items").valueStream()
                .map(e -> e.path("eventType").asText()).toList();
        assertThat(types).contains("ROLE_CHANGED", "USER_DEACTIVATED");

        // newest-first ordering + shape (actor + targetUser summaries, detail)
        JsonNode newest = events.path("items").get(0);
        assertThat(newest.path("eventType").asText()).isEqualTo("USER_DEACTIVATED");
        assertThat(newest.path("actor").path("id").asText()).isNotBlank();
        assertThat(newest.path("targetUser").path("id").asText()).isEqualTo(targetId);
        assertThat(newest.path("createdAt").asText()).isNotBlank();

        // eventType filter
        mockMvc.perform(get("/api/v1/audit-events?eventType=ROLE_CHANGED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("ROLE_CHANGED"))
                .andExpect(jsonPath("$.items[0].detail").value("Role changed to MANAGER"));
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        JsonNode org = registerOrganization("Audit Gate Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String managerEmail = uniqueEmail();
        createUser(adminToken, "Mona Manager", managerEmail, "MANAGER");
        String managerToken = loginToken(managerEmail, "password123");

        mockMvc.perform(get("/api/v1/audit-events").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void orgIsolation() throws Exception {
        JsonNode orgA = registerOrganization("Audit Iso A", uniqueEmail());
        String adminA = orgA.path("accessToken").asText();
        String targetId = createUser(adminA, "A Target", uniqueEmail(), "EMPLOYEE");
        exchange(patch("/api/v1/users/" + targetId + "/deactivate")
                .header("Authorization", "Bearer " + adminA), 204);

        JsonNode orgB = registerOrganization("Audit Iso B", uniqueEmail());
        String adminB = orgB.path("accessToken").asText();

        // B's admin sees none of A's events
        mockMvc.perform(get("/api/v1/audit-events").header("Authorization", "Bearer " + adminB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        // A's admin sees the deactivation
        mockMvc.perform(get("/api/v1/audit-events").header("Authorization", "Bearer " + adminA))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("USER_DEACTIVATED"));
    }
}
