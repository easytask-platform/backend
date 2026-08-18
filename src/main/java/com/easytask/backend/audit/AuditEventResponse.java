package com.easytask.backend.audit;

import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AvatarUrls;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        String eventType,
        UserSummary actor,
        UserSummary targetUser,
        String detail,
        Instant createdAt
) {

    public record UserSummary(UUID id, String fullName, String avatarUrl) {

        static UserSummary from(AppUser user) {
            return user == null ? null
                    : new UserSummary(user.getId(), user.getFullName(), AvatarUrls.of(user));
        }
    }

    public static AuditEventResponse from(SecurityAuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getEventType(),
                UserSummary.from(event.getActor()),
                UserSummary.from(event.getTargetUser()),
                event.getDetail(),
                event.getCreatedAt());
    }
}
