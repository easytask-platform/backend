package com.easytask.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helper base for MockMvc integration tests. Concrete subclasses must carry
 * {@code @SpringBootTest}, {@code @AutoConfigureMockMvc}, {@code @ActiveProfiles("test")}
 * and {@code @Import(TestDatabaseConfiguration.class)} themselves.
 */
public abstract class IntegrationTestSupport {

    protected static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected String uniqueEmail() {
        return "user" + COUNTER.incrementAndGet() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
    }

    /** Registers a fresh organization; returns the full response (tokens, user, organization). */
    protected JsonNode registerOrganization(String orgName, String adminEmail) throws Exception {
        return exchange(post("/api/v1/auth/register-organization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationName": "%s",
                                  "adminFullName": "Admin %s",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(orgName, orgName, adminEmail)),
                201);
    }

    /** Creates a user via POST /users as the given admin; returns the user id. Password: password123. */
    protected String createUser(String adminToken, String fullName, String email, String role) throws Exception {
        JsonNode body = exchange(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "%s",
                                  "email": "%s",
                                  "initialPassword": "password123",
                                  "role": "%s"
                                }
                                """.formatted(fullName, email, role)),
                201);
        return body.path("id").asText();
    }

    protected String loginToken(String email, String password) throws Exception {
        return exchange(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)),
                200)
                .path("accessToken").asText();
    }

    /** Performs the request, asserts the status, and parses the body (empty object for empty bodies). */
    protected JsonNode exchange(RequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
    }
}
