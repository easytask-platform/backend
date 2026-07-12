package com.easytask.backend.notification;

import java.util.UUID;

/**
 * Published (one per recipient) inside the transaction that saved the
 * notification row; consumed after commit by {@link PushDispatcher}.
 */
public record NotificationCreatedEvent(
        UUID notificationId,
        UUID recipientId,
        UUID taskId,
        String title,
        String message
) {
}
