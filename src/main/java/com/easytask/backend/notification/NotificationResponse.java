package com.easytask.backend.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String title,
        String message,
        UUID relatedTaskId,
        boolean read,
        Instant createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getTitle(),
                notification.getMessage(),
                notification.getTask() == null ? null : notification.getTask().getId(),
                notification.isRead(), notification.getCreatedAt());
    }
}
