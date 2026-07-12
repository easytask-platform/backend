package com.easytask.backend.notification;

import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.PageResponse;
import com.easytask.backend.task.Task;
import com.easytask.backend.user.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Writes notifications synchronously inside the caller's transaction.
 * The actor never receives a notification for their own action.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID userId, Boolean read, Pageable pageable) {
        Page<Notification> page = read == null
                ? notificationRepository.findAllByRecipientId(userId, pageable)
                : notificationRepository.findAllByRecipientIdAndRead(userId, read, pageable);
        return PageResponse.from(page, NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notificationRepository.countByRecipientIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findByIdAndRecipientId(notificationId, userId)
                .orElseThrow(() -> new NotFoundException("Notification not found"));
        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(Instant.now());
        }
    }

    @Transactional
    public void markAllRead(UUID userId) {
        notificationRepository.markAllRead(userId, Instant.now());
    }

    public void notifyAllExceptActor(Collection<AppUser> recipients, UUID actorId, Task task,
                                     NotificationType type, String message) {
        String title = task.getTitle().length() > 150 ? task.getTitle().substring(0, 150) : task.getTitle();
        recipients.stream()
                .filter(recipient -> !recipient.getId().equals(actorId))
                .distinct()
                .forEach(recipient -> {
                    Notification saved = notificationRepository.save(Notification.builder()
                            .recipient(recipient)
                            .task(task)
                            .type(type)
                            .title(title)
                            .message(message)
                            .build());
                    eventPublisher.publishEvent(new NotificationCreatedEvent(
                            saved.getId(), recipient.getId(), task.getId(), title, message));
                });
    }
}
