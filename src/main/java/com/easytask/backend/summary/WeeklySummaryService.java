package com.easytask.backend.summary;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.task.Task;
import com.easytask.backend.task.TaskRepository;
import com.easytask.backend.task.TaskService;
import com.easytask.backend.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Personal weekly summary (P4-9, D38). Buckets:
 * <ul>
 *   <li><b>completed</b> — tasks assigned to the caller that moved to APPROVED
 *       during the week, detected via the {@code TASK_APPROVED} activity log
 *       (the reliable approval signal: it is written exactly once, in the same
 *       transaction as the terminal transition into APPROVED).</li>
 *   <li><b>overdue</b> — assigned, due before today, not APPROVED/CANCELLED.</li>
 *   <li><b>upcoming</b> — assigned, due within [today, weekEnd], not closed.</li>
 * </ul>
 * The week is an ISO week (Monday start). A {@code weekStart} that is not a
 * Monday is snapped back to that week's Monday.
 */
@Service
@RequiredArgsConstructor
public class WeeklySummaryService {

    private static final List<TaskStatus> CLOSED_STATUSES =
            List.of(TaskStatus.APPROVED, TaskStatus.CANCELLED);

    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public WeeklySummaryResponse weeklySummary(AuthenticatedUser principal, LocalDate requestedWeekStart) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate weekStart = mondayOf(requestedWeekStart == null ? today : requestedWeekStart);
        LocalDate weekEnd = weekStart.plusDays(6);

        Instant fromInstant = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = weekEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Task> completed = taskRepository.findApprovedForAssigneeBetween(
                principal.id(), fromInstant, toInstant);
        List<Task> overdue = taskRepository.findOverdueAssignedTo(
                principal.id(), today, CLOSED_STATUSES);
        List<Task> upcoming = taskRepository.findUpcomingAssignedTo(
                principal.id(), today, weekEnd, CLOSED_STATUSES);

        return new WeeklySummaryResponse(weekStart, weekEnd,
                taskService.toListItems(principal, completed),
                taskService.toListItems(principal, overdue),
                taskService.toListItems(principal, upcoming));
    }

    private static LocalDate mondayOf(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.MONDAY
                ? date
                : date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
