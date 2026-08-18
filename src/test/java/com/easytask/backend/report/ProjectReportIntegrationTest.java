package com.easytask.backend.report;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P4-12 (D40): project report — text/html for format=html, application/pdf for pdf; permission + org isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class ProjectReportIntegrationTest extends IntegrationTestSupport {

    private record Fixture(String adminToken, String employeeToken, String projectId) {
    }

    private Fixture fixture(String orgName) throws Exception {
        JsonNode org = registerOrganization(orgName, uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String employeeEmail = uniqueEmail();
        String employeeId = createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");
        String projectId = exchange(post("/api/v1/projects").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Report Project"}
                                """),
                201).path("id").asText();
        exchange(post("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%s"}
                                """.formatted(employeeId)),
                201);
        exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "لوحة المهام", "assigneeIds": ["%s"]}
                                """.formatted(projectId, employeeId)),
                201);
        return new Fixture(adminToken, loginToken(employeeEmail, "password123"), projectId);
    }

    @Test
    void htmlReportReturnsHtml() throws Exception {
        Fixture fx = fixture("Report HTML Org");
        MvcResult result = mockMvc.perform(get("/api/v1/projects/" + fx.projectId() + "/report?format=html")
                        .header("Authorization", "Bearer " + fx.adminToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Report Project").contains("Status summary").contains("Team progress");
        // Arabic task title survives to the HTML body
        assertThat(html).contains("لوحة المهام");
    }

    @Test
    void pdfReportReturnsPdfAttachment() throws Exception {
        Fixture fx = fixture("Report PDF Org");
        MvcResult result = mockMvc.perform(get("/api/v1/projects/" + fx.projectId() + "/report")
                        .header("Authorization", "Bearer " + fx.adminToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(100);
        // PDF magic number
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void requiresManagerPermission() throws Exception {
        Fixture fx = fixture("Report Perm Org");
        mockMvc.perform(get("/api/v1/projects/" + fx.projectId() + "/report?format=html")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossOrgIsNotFound() throws Exception {
        Fixture fx = fixture("Report Iso Org");
        JsonNode orgB = registerOrganization("Report Org B", uniqueEmail());
        mockMvc.perform(get("/api/v1/projects/" + fx.projectId() + "/report?format=html")
                        .header("Authorization", "Bearer " + orgB.path("accessToken").asText()))
                .andExpect(status().isNotFound());
    }
}
