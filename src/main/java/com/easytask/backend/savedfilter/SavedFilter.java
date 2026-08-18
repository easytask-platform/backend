package com.easytask.backend.savedfilter;

import com.easytask.backend.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * A per-user saved task filter (P4-8, D42). {@code filters} stores the client's
 * opaque task-filter object as serialized JSON text; the server validates only
 * name and size, never the shape.
 */
@Entity
@Table(name = "saved_filters")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavedFilter {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false, length = 50)
    private String name;

    /** The client filter object, serialized as JSON text (≤ 2000 chars). */
    @Column(nullable = false, columnDefinition = "text")
    private String filters;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
