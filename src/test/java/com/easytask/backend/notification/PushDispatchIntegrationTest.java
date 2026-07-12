package com.easytask.backend.notification;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Full chain: HTTP comment -> notification row committed -> after-commit event
 * -> async PushDispatcher -> FirebaseMessaging (mocked) receives a multicast.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class PushDispatchIntegrationTest extends IntegrationTestSupport {

    @MockitoBean
    private FirebaseMessaging firebaseMessaging;

    @Test
    void commentingTriggersPushToAssigneeDevices() throws Exception {
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getResponses()).thenReturn(java.util.List.of());
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batch);

        // org where the admin assigns and comments; the employee is the recipient
        JsonNode org = registerOrganization("PushOrg" + COUNTER.incrementAndGet(), uniqueEmail());
        String adminToken = org.path("accessToken").asText();
        String employeeEmail = uniqueEmail();
        String employeeId = createUser(adminToken, "Push Employee", employeeEmail, "EMPLOYEE");

        String projectId = exchange(post("/api/v1/projects").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Push Project"}
                                """),
                201).path("id").asText();
        exchange(post("/api/v1/projects/" + projectId + "/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%s"}
                                """.formatted(employeeId)),
                201);

        // employee registers a device
        String employeeToken = loginToken(employeeEmail, "password123");
        exchange(post("/api/v1/me/devices")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "push-test-device", "platform": "ANDROID"}
                                """),
                204);

        // manager creates a task assigned to the employee, then comments on it
        String taskId = exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Push task", "assigneeIds": ["%s"]}
                                """.formatted(projectId, employeeId)),
                201).path("id").asText();
        exchange(post("/api/v1/tasks/" + taskId + "/comments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Please start today."}
                                """),
                201);

        // the dispatcher runs async after commit — await the FCM call
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(firebaseMessaging, atLeastOnce()).sendEachForMulticast(any(MulticastMessage.class)));
    }
}
