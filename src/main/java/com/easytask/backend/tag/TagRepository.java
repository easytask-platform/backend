package com.easytask.backend.tag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    List<Tag> findAllByProjectIdOrderByNameAsc(UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    /** Batch load for task lists: rows of {@code [taskId, Tag]} for the given tasks. */
    @Query("select task.id, tag from Task task join task.tags tag where task.id in :taskIds order by tag.name")
    List<Object[]> findTaskTagPairs(@Param("taskIds") Collection<UUID> taskIds);
}
