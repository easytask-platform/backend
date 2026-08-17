package com.easytask.backend.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TaskActivityLogRepository extends JpaRepository<TaskActivityLog, UUID> {

    List<TaskActivityLog> findAllByTaskIdOrderByCreatedAtDesc(UUID taskId);

    /** Org-wide feed (P3-4): admins see everything in the organization. */
    @Query("""
            select a from TaskActivityLog a
            where a.task.project.organization.id = :organizationId
            order by a.createdAt desc""")
    Page<TaskActivityLog> findFeedByOrganization(@Param("organizationId") UUID organizationId,
                                                 Pageable pageable);

    /** Managed feed: activity on tasks inside the given projects. */
    @Query("""
            select a from TaskActivityLog a
            where a.task.project.id in :projectIds
            order by a.createdAt desc""")
    Page<TaskActivityLog> findFeedByProjects(@Param("projectIds") Collection<UUID> projectIds,
                                             Pageable pageable);

    /** Assigned feed: activity on tasks the user is assigned to. */
    @Query("""
            select a from TaskActivityLog a
            where a.task.id in (select ta.task.id from TaskAssignment ta where ta.assignee.id = :userId)
            order by a.createdAt desc""")
    Page<TaskActivityLog> findFeedByAssignee(@Param("userId") UUID userId, Pageable pageable);
}
