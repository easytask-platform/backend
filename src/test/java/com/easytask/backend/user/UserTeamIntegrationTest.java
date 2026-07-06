package com.easytask.backend.user;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class UserTeamIntegrationTest extends IntegrationTestSupport {

    private static List<String> names(JsonNode listResponse, String field) {
        List<String> values = new ArrayList<>();
        listResponse.path("items").forEach(item -> values.add(item.path(field).asText()));
        return values;
    }

    @Test
    void adminCreatesAndListsUsersWithFilters() throws Exception {
        String adminEmail = uniqueEmail();
        JsonNode org = registerOrganization("List Org", adminEmail);
        String adminToken = org.path("accessToken").asText();

        String managerEmail = uniqueEmail();
        String employeeEmail = uniqueEmail();
        createUser(adminToken, "Mona Manager", managerEmail, "MANAGER");
        createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");

        JsonNode all = exchange(get("/api/v1/users").header("Authorization", "Bearer " + adminToken), 200);
        assertThat(all.path("totalItems").asLong()).isEqualTo(3);
        assertThat(names(all, "email")).contains(adminEmail, managerEmail, employeeEmail);

        JsonNode managersOnly = exchange(get("/api/v1/users?role=MANAGER")
                .header("Authorization", "Bearer " + adminToken), 200);
        assertThat(names(managersOnly, "fullName")).containsExactly("Mona Manager");

        JsonNode searched = exchange(get("/api/v1/users?search=sam")
                .header("Authorization", "Bearer " + adminToken), 200);
        assertThat(names(searched, "fullName")).containsExactly("Sam Employee");
    }

    @Test
    void nonAdminsCannotCreateUsersAndEmployeesCannotList() throws Exception {
        JsonNode org = registerOrganization("Forbidden Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();

        String managerEmail = uniqueEmail();
        String employeeEmail = uniqueEmail();
        createUser(adminToken, "Mona Manager", managerEmail, "MANAGER");
        createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");
        String managerToken = loginToken(managerEmail, "password123");
        String employeeToken = loginToken(employeeEmail, "password123");

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String createBody = """
                {"fullName": "New User", "email": "%s", "initialPassword": "password123", "role": "EMPLOYEE"}
                """.formatted(uniqueEmail());
        mockMvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateUserEmailReturnsConflict() throws Exception {
        JsonNode org = registerOrganization("Dup Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String email = uniqueEmail();
        createUser(adminToken, "First User", email, "EMPLOYEE");

        mockMvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Second User", "email": "%s", "initialPassword": "password123", "role": "EMPLOYEE"}
                                """.formatted(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void managerSeesOnlyUsersInManagedTeams() throws Exception {
        JsonNode org = registerOrganization("Scope Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();

        String managerEmail = uniqueEmail();
        String managerId = createUser(adminToken, "Mona Manager", managerEmail, "MANAGER");
        String inTeamId = createUser(adminToken, "Ina Team", uniqueEmail(), "EMPLOYEE");
        String outOfTeamId = createUser(adminToken, "Otto Outside", uniqueEmail(), "EMPLOYEE");

        JsonNode team = exchange(post("/api/v1/teams").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Scope Team", "description": "d"}
                                """),
                201);
        String teamId = team.path("id").asText();
        for (String userId : List.of(managerId, inTeamId)) {
            exchange(post("/api/v1/teams/" + teamId + "/members")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId": "%s"}
                                    """.formatted(userId)),
                    201);
        }

        String managerToken = loginToken(managerEmail, "password123");
        JsonNode visible = exchange(get("/api/v1/users").header("Authorization", "Bearer " + managerToken), 200);
        assertThat(names(visible, "fullName")).containsExactlyInAnyOrder("Mona Manager", "Ina Team");

        mockMvc.perform(get("/api/v1/users/" + inTeamId).header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ina Team"));
        mockMvc.perform(get("/api/v1/users/" + outOfTeamId).header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void adminUpdatesUserButCannotChangeOwnRole() throws Exception {
        String adminEmail = uniqueEmail();
        JsonNode org = registerOrganization("Update Org", adminEmail);
        String adminToken = org.path("accessToken").asText();
        String adminId = org.path("user").path("id").asText();
        String userId = createUser(adminToken, "Sam Employee", uniqueEmail(), "EMPLOYEE");

        mockMvc.perform(patch("/api/v1/users/" + userId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Samuel Employee", "role": "MANAGER"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Samuel Employee"))
                .andExpect(jsonPath("$.role").value("MANAGER"));

        mockMvc.perform(patch("/api/v1/users/" + adminId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "EMPLOYEE"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void deactivationBlocksLoginAndGuardsEdgeCases() throws Exception {
        String adminEmail = uniqueEmail();
        JsonNode org = registerOrganization("Deactivate Org", adminEmail);
        String adminToken = org.path("accessToken").asText();
        String adminId = org.path("user").path("id").asText();
        String employeeEmail = uniqueEmail();
        String employeeId = createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");

        mockMvc.perform(patch("/api/v1/users/" + employeeId + "/deactivate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "password123"}
                                """.formatted(employeeEmail)))
                .andExpect(status().isForbidden());

        // already deactivated -> 409; self-deactivation -> 409
        mockMvc.perform(patch("/api/v1/users/" + employeeId + "/deactivate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/v1/users/" + adminId + "/deactivate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    @Test
    void teamCrudMembershipAndMemberCount() throws Exception {
        JsonNode org = registerOrganization("Team Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String user1 = createUser(adminToken, "Sam Employee", uniqueEmail(), "EMPLOYEE");
        String user2 = createUser(adminToken, "Mona Manager", uniqueEmail(), "MANAGER");

        JsonNode team = exchange(post("/api/v1/teams").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Mobile Team", "description": "Flutter delivery team"}
                                """),
                201);
        String teamId = team.path("id").asText();

        // duplicate name -> 409
        mockMvc.perform(post("/api/v1/teams").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "mobile team", "description": "x"}
                                """))
                .andExpect(status().isConflict());

        // rename
        mockMvc.perform(patch("/api/v1/teams/" + teamId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Mobile Squad"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mobile Squad"));

        // add two members; adding twice -> 409
        for (String userId : List.of(user1, user2)) {
            exchange(post("/api/v1/teams/" + teamId + "/members")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId": "%s"}
                                    """.formatted(userId)),
                    201);
        }
        mockMvc.perform(post("/api/v1/teams/" + teamId + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%s"}
                                """.formatted(user1)))
                .andExpect(status().isConflict());

        JsonNode teams = exchange(get("/api/v1/teams").header("Authorization", "Bearer " + adminToken), 200);
        assertThat(teams.path("items").get(0).path("memberCount").asLong()).isEqualTo(2);

        JsonNode members = exchange(get("/api/v1/teams/" + teamId + "/members")
                .header("Authorization", "Bearer " + adminToken), 200);
        assertThat(names(members, "fullName")).containsExactlyInAnyOrder("Sam Employee", "Mona Manager");

        // remove member; removing again -> 404
        mockMvc.perform(delete("/api/v1/teams/" + teamId + "/members/" + user1)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/teams/" + teamId + "/members/" + user1)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void managerSeesOnlyOwnTeamsAndEmployeesAreForbidden() throws Exception {
        JsonNode org = registerOrganization("Visibility Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String managerEmail = uniqueEmail();
        String managerId = createUser(adminToken, "Mona Manager", managerEmail, "MANAGER");
        String employeeEmail = uniqueEmail();
        createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");

        String ownTeamId = exchange(post("/api/v1/teams").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Managed Team"}
                                """),
                201).path("id").asText();
        String otherTeamId = exchange(post("/api/v1/teams").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Other Team"}
                                """),
                201).path("id").asText();
        exchange(post("/api/v1/teams/" + ownTeamId + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%s"}
                                """.formatted(managerId)),
                201);

        String managerToken = loginToken(managerEmail, "password123");
        JsonNode visibleTeams = exchange(get("/api/v1/teams").header("Authorization", "Bearer " + managerToken), 200);
        assertThat(names(visibleTeams, "name")).containsExactly("Managed Team");

        mockMvc.perform(get("/api/v1/teams/" + otherTeamId + "/members")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isNotFound());

        String employeeToken = loginToken(employeeEmail, "password123");
        mockMvc.perform(get("/api/v1/teams").header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossOrganizationAccessReturns404() throws Exception {
        JsonNode orgA = registerOrganization("Org A", uniqueEmail());
        String adminA = orgA.path("accessToken").asText();
        String userAId = createUser(adminA, "User In A", uniqueEmail(), "EMPLOYEE");
        String teamAId = exchange(post("/api/v1/teams").header("Authorization", "Bearer " + adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "A Team"}
                                """),
                201).path("id").asText();

        JsonNode orgB = registerOrganization("Org B", uniqueEmail());
        String adminB = orgB.path("accessToken").asText();

        mockMvc.perform(get("/api/v1/users/" + userAId).header("Authorization", "Bearer " + adminB))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/teams/" + teamAId).header("Authorization", "Bearer " + adminB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Hijacked"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/teams/" + teamAId + "/members").header("Authorization", "Bearer " + adminB))
                .andExpect(status().isNotFound());

        // org A admin cannot add an org B user to an org A team
        String userBId = createUser(adminB, "User In B", uniqueEmail(), "EMPLOYEE");
        mockMvc.perform(post("/api/v1/teams/" + teamAId + "/members")
                        .header("Authorization", "Bearer " + adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%s"}
                                """.formatted(userBId)))
                .andExpect(status().isNotFound());

        // and org A's admin listing users never contains org B's users
        JsonNode usersA = exchange(get("/api/v1/users?size=100").header("Authorization", "Bearer " + adminA), 200);
        assertThat(names(usersA, "id")).doesNotContain(userBId);

        // unknown id -> 404 as well
        mockMvc.perform(get("/api/v1/users/" + UUID.randomUUID()).header("Authorization", "Bearer " + adminA))
                .andExpect(status().isNotFound());
    }
}
