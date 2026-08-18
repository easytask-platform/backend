package com.easytask.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface TaskPinRepository extends JpaRepository<TaskPin, TaskPin.Key> {

    List<TaskPin> findAllByUserIdOrderByPinnedAtDesc(UUID userId);

    boolean existsByUserIdAndTaskId(UUID userId, UUID taskId);

    long countByUserId(UUID userId);

    void deleteByUserIdAndTaskId(UUID userId, UUID taskId);

    /** Batch lookup for the caller's {@code pinned} flag on task lists. */
    @Query("select p.taskId from TaskPin p where p.userId = :userId and p.taskId in :taskIds")
    Set<UUID> findPinnedTaskIds(@Param("userId") UUID userId, @Param("taskIds") Collection<UUID> taskIds);
}
