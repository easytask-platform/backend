package com.easytask.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    Optional<Task> findByIdAndProjectOrganizationId(UUID id, UUID organizationId);

    org.springframework.data.domain.Page<Task> findAllByRecurringTaskRuleId(
            UUID ruleId, org.springframework.data.domain.Pageable pageable);

    /** Project report (P4-12): all tasks in a project, stable order. */
    List<Task> findAllByProjectIdOrderByCreatedAtAsc(UUID projectId);

    /** Project report (P4-12): per-assignee [assigned, approved] counts for a project. */
    @Query("""
            select a.assignee.id, count(a),
                   sum(case when t.status = com.easytask.backend.task.TaskStatus.APPROVED then 1 else 0 end)
            from TaskAssignment a join a.task t
            where t.project.id = :projectId
            group by a.assignee.id""")
    List<Object[]> assigneeTaskCountsForProject(@Param("projectId") UUID projectId);

    long countByProjectId(UUID projectId);

    long countByProjectIdAndStatus(UUID projectId, TaskStatus status);

    @Query("select t.status, count(t) from Task t where t.project.id = :projectId group by t.status")
    List<Object[]> countByStatusForProject(@Param("projectId") UUID projectId);

    @Query("""
            select t.project.id, t.status, count(t) from Task t
            where t.project.id in :projectIds
            group by t.project.id, t.status""")
    List<Object[]> countByStatusForProjects(@Param("projectIds") java.util.Collection<UUID> projectIds);

    @Query("""
            select t.status, count(t) from Task t
            where t.project.organization.id = :organizationId
            group by t.status""")
    List<Object[]> countByStatusForOrganization(@Param("organizationId") UUID organizationId);

    @Query("""
            select t from Task t join fetch t.project
            where t.project.organization.id = :organizationId
              and t.dueDate < :today and t.status not in :closedStatuses
            order by t.dueDate asc""")
    List<Task> findOverdueForOrganization(@Param("organizationId") UUID organizationId,
                                          @Param("today") java.time.LocalDate today,
                                          @Param("closedStatuses") java.util.Collection<TaskStatus> closedStatuses,
                                          org.springframework.data.domain.Pageable pageable);

    long countByProjectIdInAndDueDateBeforeAndStatusNotIn(java.util.Collection<UUID> projectIds,
                                                          java.time.LocalDate today,
                                                          java.util.Collection<TaskStatus> closedStatuses);

    long countByProjectOrganizationIdAndDueDateBeforeAndStatusNotIn(UUID organizationId,
                                                                    java.time.LocalDate today,
                                                                    java.util.Collection<TaskStatus> closedStatuses);

    @Query("""
            select t.project.id, count(t) from Task t
            where t.project.id in :projectIds and t.dueDate < :today and t.status not in :closedStatuses
            group by t.project.id""")
    List<Object[]> countOverdueByProject(@Param("projectIds") java.util.Collection<UUID> projectIds,
                                         @Param("today") java.time.LocalDate today,
                                         @Param("closedStatuses") java.util.Collection<TaskStatus> closedStatuses);

    @Query("""
            select t.project.id, coalesce(sum(t.estimatedHours), 0) from Task t
            where t.project.id in :projectIds
            group by t.project.id""")
    List<Object[]> sumEstimatedHoursByProject(@Param("projectIds") java.util.Collection<UUID> projectIds);

    // --- Weekly summary (P4-9, D38): tasks assigned to the caller -------------

    /** Overdue: assigned to the user, due before today, not in a closed status. */
    @Query("""
            select distinct t from Task t
            where t.id in (select ta.task.id from TaskAssignment ta where ta.assignee.id = :userId)
              and t.dueDate is not null and t.dueDate < :today
              and t.status not in :closedStatuses
            order by t.dueDate asc""")
    List<Task> findOverdueAssignedTo(@Param("userId") UUID userId,
                                     @Param("today") java.time.LocalDate today,
                                     @Param("closedStatuses") java.util.Collection<TaskStatus> closedStatuses);

    /** Upcoming: assigned to the user, due within [today, weekEnd], not closed. */
    @Query("""
            select distinct t from Task t
            where t.id in (select ta.task.id from TaskAssignment ta where ta.assignee.id = :userId)
              and t.dueDate is not null and t.dueDate >= :today and t.dueDate <= :weekEnd
              and t.status not in :closedStatuses
            order by t.dueDate asc""")
    List<Task> findUpcomingAssignedTo(@Param("userId") UUID userId,
                                      @Param("today") java.time.LocalDate today,
                                      @Param("weekEnd") java.time.LocalDate weekEnd,
                                      @Param("closedStatuses") java.util.Collection<TaskStatus> closedStatuses);

    /**
     * Completed: tasks assigned to the user that were APPROVED during the week,
     * signalled by a {@code TASK_APPROVED} activity log within the range.
     */
    @Query("""
            select distinct t from Task t
            where t.id in (select ta.task.id from TaskAssignment ta where ta.assignee.id = :userId)
              and t.id in (
                  select a.task.id from TaskActivityLog a
                  where a.eventType = com.easytask.backend.activity.ActivityEventType.TASK_APPROVED
                    and a.createdAt >= :fromInstant and a.createdAt < :toInstant)
            order by t.dueDate asc""")
    List<Task> findApprovedForAssigneeBetween(@Param("userId") UUID userId,
                                              @Param("fromInstant") java.time.Instant fromInstant,
                                              @Param("toInstant") java.time.Instant toInstant);
}
