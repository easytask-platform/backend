package com.easytask.backend.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * A user's private daily-focus pin (P4-7, D33). Pins are invisible to other
 * users, capped at 20 per user, and write no activity or notifications.
 */
@Entity
@Table(name = "task_pins")
@IdClass(TaskPin.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskPin {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "task_id")
    private UUID taskId;

    @CreationTimestamp
    @Column(name = "pinned_at", nullable = false, updatable = false)
    private Instant pinnedAt;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID userId;
        private UUID taskId;
    }
}
