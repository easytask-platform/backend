package com.easytask.backend.user;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P4-1 (D30): avatar upload/serve/remove + avatarUrl on user payloads. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class AvatarIntegrationTest extends IntegrationTestSupport {

    // Not a real PNG; the backend validates content type + size only.
    private static final MockMultipartFile PNG =
            new MockMultipartFile("file", "me.png", "image/png", "png-bytes".getBytes());

    @Test
    void uploadServeRemoveLifecycle() throws Exception {
        JsonNode org = registerOrganization("Avatar Org", uniqueEmail());
        String token = org.path("accessToken").asText();
        String userId = org.path("user").path("id").asText();

        // no avatar yet: /me exposes null, serving 404s
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.avatarUrl").value((Object) null));
        mockMvc.perform(get("/api/v1/users/" + userId + "/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        String avatarUrl = exchange(multipart(HttpMethod.PUT, "/api/v1/me/avatar").file(PNG)
                        .header("Authorization", "Bearer " + token),
                200).path("avatarUrl").asText();
        assertThat(avatarUrl).startsWith("/api/v1/users/" + userId + "/avatar?v=");

        mockMvc.perform(get("/api/v1/users/" + userId + "/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes("png-bytes".getBytes()));
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.avatarUrl").value(avatarUrl));

        // replacing changes the cache-busting URL
        MockMultipartFile jpg = new MockMultipartFile("file", "me.jpg", "image/jpeg", "jpg-bytes".getBytes());
        String replacedUrl = exchange(multipart(HttpMethod.PUT, "/api/v1/me/avatar").file(jpg)
                        .header("Authorization", "Bearer " + token),
                200).path("avatarUrl").asText();
        assertThat(replacedUrl).isNotEqualTo(avatarUrl);

        mockMvc.perform(delete("/api/v1/me/avatar").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users/" + userId + "/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsNonImageAndOversizedFiles() throws Exception {
        JsonNode org = registerOrganization("Avatar Validation Org", uniqueEmail());
        String token = org.path("accessToken").asText();

        MockMultipartFile pdf = new MockMultipartFile("file", "cv.pdf", "application/pdf", "%PDF".getBytes());
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/me/avatar").file(pdf)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.file").exists());

        MockMultipartFile huge = new MockMultipartFile("file", "big.png", "image/png",
                new byte[(int) AvatarService.MAX_AVATAR_BYTES + 1]);
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/me/avatar").file(huge)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void avatarIsOrgScopedAndVisibleToPlainEmployees() throws Exception {
        JsonNode org = registerOrganization("Avatar Scope Org", uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String adminId = org.path("user").path("id").asText();
        exchange(multipart(HttpMethod.PUT, "/api/v1/me/avatar").file(PNG)
                .header("Authorization", "Bearer " + adminToken), 200);

        // employees have no user:read but still see avatars (comments/activity render them)
        String employeeEmail = uniqueEmail();
        createUser(adminToken, "Sam Employee", employeeEmail, "EMPLOYEE");
        String employeeToken = loginToken(employeeEmail, "password123");
        mockMvc.perform(get("/api/v1/users/" + adminId + "/avatar")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk());

        // cross-org lookups 404 per the org-isolation rule
        JsonNode otherOrg = registerOrganization("Other Avatar Org", uniqueEmail());
        String outsiderToken = otherOrg.path("accessToken").asText();
        mockMvc.perform(get("/api/v1/users/" + adminId + "/avatar")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }
}
