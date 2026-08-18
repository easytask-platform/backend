package com.easytask.backend.activity;

import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AvatarUrls;

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

    public record Actor(UUID id, String fullName, String avatarUrl) {

        public static Actor from(AppUser actor) {
            return new Actor(actor.getId(), actor.getFullName(), AvatarUrls.of(actor));
        }
    }

    public static ActivityLogResponse from(TaskActivityLog log) {
        return new ActivityLogResponse(log.getId(), log.getTask().getId(),
                Actor.from(log.getActor()),
                log.getEventType(), log.getOldValue(), log.getNewValue(), log.getCreatedAt());
    }
}
