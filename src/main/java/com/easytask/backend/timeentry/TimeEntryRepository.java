package com.easytask.backend.timeentry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID> {

    @Query("""
            select e from TimeEntry e
            join fetch e.taskAssignment a
            join fetch a.assignee
            where a.task.id = :taskId
            order by e.workDate asc, e.createdAt asc""")
    List<TimeEntry> findAllForTask(@Param("taskId") UUID taskId);

    @Query("select coalesce(sum(e.hoursSpent), 0) from TimeEntry e where e.taskAssignment.task.id = :taskId")
    BigDecimal totalHoursForTask(@Param("taskId") UUID taskId);

    @Query("""
            select e.taskAssignment.task.id, sum(e.hoursSpent) from TimeEntry e
            where e.taskAssignment.task.id in :taskIds
            group by e.taskAssignment.task.id""")
    List<Object[]> totalHoursForTasks(@Param("taskIds") java.util.Collection<UUID> taskIds);

    @Query("""
            select a.assignee.id, coalesce(sum(e.hoursSpent), 0)
            from TimeEntry e join e.taskAssignment a join a.task t
            where a.assignee.id in :userIds and t.project.id in :projectIds
              and e.workDate between :from and :to
            group by a.assignee.id""")
    List<Object[]> loggedHoursByUserForProjects(@Param("userIds") java.util.Collection<UUID> userIds,
                                                @Param("projectIds") java.util.Collection<UUID> projectIds,
                                                @Param("from") java.time.LocalDate from,
                                                @Param("to") java.time.LocalDate to);

    @Query("""
            select a.assignee.id, coalesce(sum(e.hoursSpent), 0)
            from TimeEntry e join e.taskAssignment a join a.task t
            where a.assignee.id in :userIds and t.project.organization.id = :organizationId
              and e.workDate between :from and :to
            group by a.assignee.id""")
    List<Object[]> loggedHoursByUserForOrganization(@Param("userIds") java.util.Collection<UUID> userIds,
                                                    @Param("organizationId") UUID organizationId,
                                                    @Param("from") java.time.LocalDate from,
                                                    @Param("to") java.time.LocalDate to);

    @Query("""
            select t.project.id, coalesce(sum(e.hoursSpent), 0)
            from TimeEntry e join e.taskAssignment a join a.task t
            where t.project.id in :projectIds and e.workDate between :from and :to
            group by t.project.id""")
    List<Object[]> loggedHoursByProject(@Param("projectIds") java.util.Collection<UUID> projectIds,
                                        @Param("from") java.time.LocalDate from,
                                        @Param("to") java.time.LocalDate to);
}
