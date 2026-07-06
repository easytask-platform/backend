package com.easytask.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskAssignmentRepository extends JpaRepository<TaskAssignment, UUID> {

    List<TaskAssignment> findAllByTaskId(UUID taskId);

    List<TaskAssignment> findAllByTaskIdIn(Collection<UUID> taskIds);

    Optional<TaskAssignment> findByTaskIdAndAssigneeId(UUID taskId, UUID assigneeUserId);

    boolean existsByTaskIdAndAssigneeId(UUID taskId, UUID assigneeUserId);

    boolean existsByTaskProjectIdAndAssigneeId(UUID projectId, UUID assigneeUserId);

    long countByAssigneeId(UUID assigneeUserId);

    @org.springframework.data.jpa.repository.Query("""
            select a.assignee.id, count(a),
                   sum(case when t.status = com.easytask.backend.task.TaskStatus.IN_PROGRESS then 1 else 0 end),
                   sum(case when t.dueDate is not null and t.dueDate < :today
                            and t.status not in :closedStatuses then 1 else 0 end)
            from TaskAssignment a join a.task t
            where a.assignee.id in :userIds and t.project.id in :projectIds
            group by a.assignee.id""")
    List<Object[]> workloadStatsForProjects(
            @org.springframework.data.repository.query.Param("userIds") Collection<UUID> userIds,
            @org.springframework.data.repository.query.Param("projectIds") Collection<UUID> projectIds,
            @org.springframework.data.repository.query.Param("today") java.time.LocalDate today,
            @org.springframework.data.repository.query.Param("closedStatuses") Collection<TaskStatus> closedStatuses);

    @org.springframework.data.jpa.repository.Query("""
            select a.assignee.id, count(a),
                   sum(case when t.status = com.easytask.backend.task.TaskStatus.IN_PROGRESS then 1 else 0 end),
                   sum(case when t.dueDate is not null and t.dueDate < :today
                            and t.status not in :closedStatuses then 1 else 0 end)
            from TaskAssignment a join a.task t
            where a.assignee.id in :userIds and t.project.organization.id = :organizationId
            group by a.assignee.id""")
    List<Object[]> workloadStatsForOrganization(
            @org.springframework.data.repository.query.Param("userIds") Collection<UUID> userIds,
            @org.springframework.data.repository.query.Param("organizationId") UUID organizationId,
            @org.springframework.data.repository.query.Param("today") java.time.LocalDate today,
            @org.springframework.data.repository.query.Param("closedStatuses") Collection<TaskStatus> closedStatuses);
}
