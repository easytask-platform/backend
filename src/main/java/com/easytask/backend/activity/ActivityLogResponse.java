package com.easytask.backend.activity;

import java.time.Instant;
import java.util.UUID;

public record ActivityLogResponse(
        UUID id,
        UUID taskId,
        Actor actor,
        ActivityEventType eventType,
        String oldValue,
        String newValue,
        Instant createdAt
) {

    public record Actor(UUID id, String fullName) {
    }

    public static ActivityLogResponse from(TaskActivityLog log) {
        return new ActivityLogResponse(log.getId(), log.getTask().getId(),
                new Actor(log.getActor().getId(), log.getActor().getFullName()),
                log.getEventType(), log.getOldValue(), log.getNewValue(), log.getCreatedAt());
    }
}
