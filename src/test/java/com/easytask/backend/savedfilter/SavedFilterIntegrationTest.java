package com.easytask.backend.savedfilter;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P4-8 (D42): per-user saved filters — CRUD, dup-name/cap 409, owner isolation, nested JSON. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class SavedFilterIntegrationTest extends IntegrationTestSupport {

    private String create(String token, String name, String filtersJson) throws Exception {
        return exchange(post("/api/v1/me/saved-filters")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "filters": %s}
                                """.formatted(name, filtersJson)),
                201).path("id").asText();
    }

    @Test
    void crudAndNestedJsonFilters() throws Exception {
        JsonNode org = registerOrganization("SF Org", uniqueEmail());
        String token = org.path("accessToken").asText();

        // create returns 201 with the filters nested as a JSON OBJECT (not a string)
        mockMvc.perform(post("/api/v1/me/saved-filters")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Overdue", "filters": {"overdue": true, "sort": "dueDate"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Overdue"))
                .andExpect(jsonPath("$.filters.overdue").value(true))
                .andExpect(jsonPath("$.filters.sort").value("dueDate"))
                .andExpect(jsonPath("$.createdAt").exists());

        String secondId = create(token, "Critical", "{\"priority\": \"CRITICAL\"}");

        // list is newest-first and returns filters as objects
        mockMvc.perform(get("/api/v1/me/saved-filters").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Critical"))
                .andExpect(jsonPath("$.items[0].filters.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.items[1].name").value("Overdue"));

        // delete
        mockMvc.perform(delete("/api/v1/me/saved-filters/" + secondId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/me/saved-filters").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void duplicateNameIsConflict() throws Exception {
        JsonNode org = registerOrganization("SF Dup Org", uniqueEmail());
        String token = org.path("accessToken").asText();
        create(token, "Mine", "{\"a\": 1}");
        mockMvc.perform(post("/api/v1/me/saved-filters")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Mine", "filters": {"b": 2}}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void capOfTwentyIsConflict() throws Exception {
        JsonNode org = registerOrganization("SF Cap Org", uniqueEmail());
        String token = org.path("accessToken").asText();
        for (int i = 0; i < 20; i++) {
            create(token, "Filter " + i, "{\"i\": " + i + "}");
        }
        mockMvc.perform(post("/api/v1/me/saved-filters")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "One too many", "filters": {}}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void ownerIsolation() throws Exception {
        JsonNode orgA = registerOrganization("SF Owner A", uniqueEmail());
        String tokenA = orgA.path("accessToken").asText();
        JsonNode orgB = registerOrganization("SF Owner B", uniqueEmail());
        String tokenB = orgB.path("accessToken").asText();

        String idA = create(tokenA, "A's filter", "{\"x\": 1}");

        // B cannot see A's filter
        mockMvc.perform(get("/api/v1/me/saved-filters").header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.items.length()").value(0));
        // B cannot delete A's filter -> 404
        mockMvc.perform(delete("/api/v1/me/saved-filters/" + idA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        // A's filter still there
        mockMvc.perform(get("/api/v1/me/saved-filters").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.items.length()").value(1));
    }
}
