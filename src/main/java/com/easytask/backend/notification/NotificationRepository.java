package com.easytask.backend.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findAllByRecipientId(UUID recipientUserId, Pageable pageable);

    Page<Notification> findAllByRecipientIdAndRead(UUID recipientUserId, boolean read, Pageable pageable);

    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientUserId);

    long countByRecipientIdAndReadFalse(UUID recipientUserId);

    @Modifying
    @Query("""
            update Notification n set n.read = true, n.readAt = :now
            where n.recipient.id = :userId and n.read = false""")
    void markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);
}
