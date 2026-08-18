package com.easytask.backend.task;

import com.easytask.backend.IntegrationTestSupport;
import com.easytask.backend.TestDatabaseConfiguration;
import com.easytask.backend.notification.Notification;
import com.easytask.backend.notification.NotificationRepository;
import com.easytask.backend.notification.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M8: comments, attachments, time entries, and the activity read endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
class TaskCollaborationIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private NotificationRepository notificationRepository;

    private record Fixture(String adminToken, String managerToken, String employeeToken,
                           String managerId, String employeeId, String projectId, String taskId) {
    }

    /** Notifications for the user, minus the TASK_ASSIGNED one from the fixture's task creation. */
    private List<Notification> commentNotificationsFor(String userId) {
        return notificationRepository
                .findAllByRecipientId(UUID.fromString(userId), Pageable.unpaged()).getContent().stream()
                .filter(notification -> notification.getType() != NotificationType.TASK_ASSIGNED)
                .toList();
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
        for (String userId : List.of(managerId, employeeId)) {
            exchange(post("/api/v1/projects/" + projectId + "/members")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId": "%s"}
                                    """.formatted(userId)),
                    201);
        }
        String managerToken = loginToken(managerEmail, "password123");
        String taskId = exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Collab task", "estimatedHours": 6,
                                 "assigneeIds": ["%s"]}
                                """.formatted(projectId, employeeId)),
                201).path("id").asText();
        return new Fixture(adminToken, managerToken, loginToken(employeeEmail, "password123"),
                managerId, employeeId, projectId, taskId);
    }

    @Test
    void commentCrudIsAuthorScoped() throws Exception {
        Fixture fx = fixture("Comment Org");

        String commentId = exchange(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "I started this task."}
                                """),
                201).path("id").asText();

        // list shape
        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].text").value("I started this task."))
                .andExpect(jsonPath("$.items[0].author.fullName").value("Sam Employee"));

        // author edits; non-author cannot edit or delete
        mockMvc.perform(patch("/api/v1/comments/" + commentId)
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Updated comment text."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Updated comment text."));
        mockMvc.perform(patch("/api/v1/comments/" + commentId)
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "hijack"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/comments/" + commentId)
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isForbidden());

        // author deletes
        mockMvc.perform(delete("/api/v1/comments/" + commentId)
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void replyLifecycleValidationAndCascadeDelete() throws Exception {
        Fixture fx = fixture("Reply Org");

        String parentId = exchange(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Top-level comment."}
                                """),
                201).path("id").asText();

        JsonNode reply = exchange(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Replying to you.", "parentCommentId": "%s"}
                                """.formatted(parentId)),
                201);
        assertThat(reply.path("parentCommentId").asText()).isEqualTo(parentId);
        String replyId = reply.path("id").asText();

        // flat list carries parentCommentId (null for top-level)
        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].parentCommentId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[1].parentCommentId").value(parentId));

        // one-level threading: replying to a reply is rejected
        mockMvc.perform(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Too deep.", "parentCommentId": "%s"}
                                """.formatted(replyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.parentCommentId").exists());

        // parent must be a comment on the same task
        String otherTaskId = exchange(post("/api/v1/tasks").header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "title": "Other task", "assigneeIds": ["%s"]}
                                """.formatted(fx.projectId(), fx.employeeId())),
                201).path("id").asText();
        mockMvc.perform(post("/api/v1/tasks/" + otherTaskId + "/comments")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Cross-task reply.", "parentCommentId": "%s"}
                                """.formatted(parentId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.parentCommentId").exists());

        // deleting the parent cascades to its replies
        mockMvc.perform(delete("/api/v1/comments/" + parentId)
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void replyNotifiesParentAuthorInsteadOfCommentAdded() throws Exception {
        Fixture fx = fixture("Reply Notify Org");

        // employee's top-level comment: manager (task creator) gets the generic fan-out
        String parentId = exchange(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Top-level comment."}
                                """),
                201).path("id").asText();

        exchange(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Replying to you.", "parentCommentId": "%s"}
                                """.formatted(parentId)),
                201);

        // parent author gets COMMENT_REPLIED only — excluded from COMMENT_ADDED
        List<Notification> employeeNotifications = commentNotificationsFor(fx.employeeId());
        assertThat(employeeNotifications).hasSize(1);
        assertThat(employeeNotifications.get(0).getType()).isEqualTo(NotificationType.COMMENT_REPLIED);
        assertThat(employeeNotifications.get(0).getMessage())
                .isEqualTo("Mona Manager replied to your comment on task 'Collab task'");

        // manager only has the COMMENT_ADDED from the employee's comment, nothing for own reply
        var managerTypes = commentNotificationsFor(fx.managerId()).stream().map(Notification::getType).toList();
        assertThat(managerTypes).containsExactly(NotificationType.COMMENT_ADDED);
    }

    @Test
    void mentionsNotifyAndValidateVisibility() throws Exception {
        Fixture fx = fixture("Mention Org");

        // manager mentions the employee: COMMENT_MENTION only, no COMMENT_ADDED double
        exchange(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Ping @Sam Employee", "mentionedUserIds": ["%s"]}
                                """.formatted(fx.employeeId())),
                201);
        List<Notification> employeeNotifications = commentNotificationsFor(fx.employeeId());
        assertThat(employeeNotifications).hasSize(1);
        assertThat(employeeNotifications.get(0).getType()).isEqualTo(NotificationType.COMMENT_MENTION);
        assertThat(employeeNotifications.get(0).getMessage())
                .isEqualTo("You were mentioned in a comment on task 'Collab task'");
        assertThat(commentNotificationsFor(fx.managerId())).isEmpty();

        // self-mention is silently ignored: no notification for the actor
        exchange(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Note to self", "mentionedUserIds": ["%s"]}
                                """.formatted(fx.employeeId())),
                201);
        assertThat(commentNotificationsFor(fx.employeeId())).hasSize(1);

        // same-org user without task visibility cannot be mentioned
        String outsiderId = createUser(fx.adminToken(), "Out Sider", uniqueEmail(), "EMPLOYEE");
        mockMvc.perform(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Ping @Out Sider", "mentionedUserIds": ["%s"]}
                                """.formatted(outsiderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.mentionedUserIds").exists());
        assertThat(commentNotificationsFor(outsiderId)).isEmpty();

        // unknown / other-org id is rejected the same way
        mockMvc.perform(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Ping @nobody", "mentionedUserIds": ["%s"]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.mentionedUserIds").exists());
    }

    @Test
    void attachmentUploadDownloadDeleteLifecycle() throws Exception {
        Fixture fx = fixture("Attach Org");
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello attachment".getBytes());

        String attachmentId = exchange(multipart("/api/v1/tasks/" + fx.taskId() + "/attachments").file(file)
                        .header("Authorization", "Bearer " + fx.employeeToken()),
                201).path("id").asText();

        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId() + "/attachments")
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].originalFilename").value("notes.txt"))
                .andExpect(jsonPath("$.items[0].contentType").value("text/plain"))
                .andExpect(jsonPath("$.items[0].uploader.fullName").value("Sam Employee"));

        mockMvc.perform(get("/api/v1/attachments/" + attachmentId + "/download")
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("notes.txt")))
                .andExpect(content().bytes("hello attachment".getBytes()));

        // disallowed type -> 400
        MockMultipartFile exe = new MockMultipartFile("file", "run.exe", "application/x-msdownload",
                new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/v1/tasks/" + fx.taskId() + "/attachments").file(exe)
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // only uploader deletes
        mockMvc.perform(delete("/api/v1/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/attachments/" + attachmentId + "/download")
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void attachmentDeletionBlockedAfterApproval() throws Exception {
        Fixture fx = fixture("Attach Lock Org");
        MockMultipartFile file = new MockMultipartFile("file", "spec.txt", "text/plain", "spec".getBytes());
        String attachmentId = exchange(multipart("/api/v1/tasks/" + fx.taskId() + "/attachments").file(file)
                        .header("Authorization", "Bearer " + fx.employeeToken()),
                201).path("id").asText();

        for (String status : List.of("IN_PROGRESS", "IN_REVIEW")) {
            exchange(patch("/api/v1/tasks/" + fx.taskId() + "/status")
                            .header("Authorization", "Bearer " + fx.employeeToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "%s"}
                                    """.formatted(status)),
                    200);
        }
        exchange(patch("/api/v1/tasks/" + fx.taskId() + "/status")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "APPROVED"}
                                """),
                200);

        mockMvc.perform(delete("/api/v1/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void timeEntryLifecycleAndPermissions() throws Exception {
        Fixture fx = fixture("Time Org");

        // non-assignee (manager) cannot log time
        mockMvc.perform(post("/api/v1/tasks/" + fx.taskId() + "/time-entries")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workDate": "2026-07-01", "hoursSpent": 2, "note": "n"}
                                """))
                .andExpect(status().isForbidden());

        // > 24h rejected
        mockMvc.perform(post("/api/v1/tasks/" + fx.taskId() + "/time-entries")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workDate": "2026-07-01", "hoursSpent": 25}
                                """))
                .andExpect(status().isBadRequest());

        String entryId = exchange(post("/api/v1/tasks/" + fx.taskId() + "/time-entries")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workDate": "2026-07-01", "hoursSpent": 2, "note": "Built login form"}
                                """),
                201).path("id").asText();
        exchange(post("/api/v1/tasks/" + fx.taskId() + "/time-entries")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workDate": "2026-07-02", "hoursSpent": 3.5}
                                """),
                201);

        // list has items + totals
        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId() + "/time-entries")
                        .header("Authorization", "Bearer " + fx.managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalLoggedHours").value(5.5))
                .andExpect(jsonPath("$.estimatedHours").value(6))
                .andExpect(jsonPath("$.items[0].employee.fullName").value("Sam Employee"));

        // totalLoggedHours also appears in the task list payload
        mockMvc.perform(get("/api/v1/tasks?search=Collab").header("Authorization", "Bearer " + fx.adminToken()))
                .andExpect(jsonPath("$.items[0].totalLoggedHours").value(5.5));

        // owner edits; non-owner cannot
        mockMvc.perform(patch("/api/v1/time-entries/" + entryId)
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hoursSpent": 4}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hoursSpent").value(4));
        mockMvc.perform(patch("/api/v1/time-entries/" + entryId)
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hoursSpent": 1}
                                """))
                .andExpect(status().isForbidden());

        // lock after approval
        for (String status : List.of("IN_PROGRESS", "IN_REVIEW")) {
            exchange(patch("/api/v1/tasks/" + fx.taskId() + "/status")
                            .header("Authorization", "Bearer " + fx.employeeToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "%s"}
                                    """.formatted(status)),
                    200);
        }
        exchange(patch("/api/v1/tasks/" + fx.taskId() + "/status")
                        .header("Authorization", "Bearer " + fx.managerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "APPROVED"}
                                """),
                200);
        mockMvc.perform(delete("/api/v1/time-entries/" + entryId)
                        .header("Authorization", "Bearer " + fx.employeeToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void activityEndpointExposesFullTrail() throws Exception {
        Fixture fx = fixture("Activity Org");

        exchange(post("/api/v1/tasks/" + fx.taskId() + "/comments")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "working on it"}
                                """),
                201);
        exchange(post("/api/v1/tasks/" + fx.taskId() + "/time-entries")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workDate": "2026-07-01", "hoursSpent": 1}
                                """),
                201);
        exchange(patch("/api/v1/tasks/" + fx.taskId() + "/status")
                        .header("Authorization", "Bearer " + fx.employeeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "IN_PROGRESS"}
                                """),
                200);

        JsonNode activity = exchange(get("/api/v1/tasks/" + fx.taskId() + "/activity")
                .header("Authorization", "Bearer " + fx.managerToken()), 200);
        List<String> events = activity.path("items").valueStream()
                .map(item -> item.path("eventType").asText())
                .toList();
        assertThat(events).contains("TASK_CREATED", "ASSIGNEE_ADDED", "COMMENT_POSTED", "TIME_LOGGED",
                "STATUS_CHANGED");

        JsonNode statusChange = activity.path("items").valueStream()
                .filter(item -> item.path("eventType").asText().equals("STATUS_CHANGED"))
                .findFirst().orElseThrow();
        assertThat(statusChange.path("oldValue").asText()).isEqualTo("TO_DO");
        assertThat(statusChange.path("newValue").asText()).isEqualTo("IN_PROGRESS");
        assertThat(statusChange.path("actor").path("fullName").asText()).isEqualTo("Sam Employee");

        // outsider (other org) cannot read the trail
        JsonNode orgB = registerOrganization("Activity Org B", uniqueEmail());
        mockMvc.perform(get("/api/v1/tasks/" + fx.taskId() + "/activity")
                        .header("Authorization", "Bearer " + orgB.path("accessToken").asText()))
                .andExpect(status().isNotFound());
    }
}
