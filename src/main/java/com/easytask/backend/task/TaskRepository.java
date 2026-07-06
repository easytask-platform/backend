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
}
