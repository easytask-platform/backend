package com.easytask.backend.activity;

import java.time.Instant;
import java.util.UUID;

/** One row of the org-wide recent-activity feed (P3-4/D27). */
public record OrgActivityItemResponse(
        UUID id,
        UUID taskId,
        String taskTitle,
        String projectName,
        ActivityLogResponse.Actor actor,
        ActivityEventType eventType,
        String oldValue,
        String newValue,
        Instant createdAt
) {

    public static OrgActivityItemResponse from(TaskActivityLog log) {
        return new OrgActivityItemResponse(log.getId(), log.getTask().getId(),
                log.getTask().getTitle(), log.getTask().getProject().getName(),
                ActivityLogResponse.Actor.from(log.getActor()),
                log.getEventType(), log.getOldValue(), log.getNewValue(), log.getCreatedAt());
    }
}
