package com.easytask.backend.checklist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TaskChecklistItemRepository extends JpaRepository<TaskChecklistItem, UUID> {

    List<TaskChecklistItem> findAllByTaskIdOrderByPositionAsc(UUID taskId);

    long countByTaskId(UUID taskId);

    long countByTaskIdAndDoneTrue(UUID taskId);

    @Query("select coalesce(max(i.position), 0) from TaskChecklistItem i where i.task.id = :taskId")
    int maxPosition(@Param("taskId") UUID taskId);

    /** Batch progress for task lists: rows of {@code [taskId, doneCount, totalCount]}. */
    @Query("select i.task.id, sum(case when i.done = true then 1 else 0 end), count(i) "
            + "from TaskChecklistItem i where i.task.id in :taskIds group by i.task.id")
    List<Object[]> countsByTask(@Param("taskIds") Collection<UUID> taskIds);
}
