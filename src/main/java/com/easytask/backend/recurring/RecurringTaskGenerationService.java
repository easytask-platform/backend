package com.easytask.backend.recurring;

import com.easytask.backend.activity.ActivityEventType;
import com.easytask.backend.activity.ActivityService;
import com.easytask.backend.notification.NotificationService;
import com.easytask.backend.notification.NotificationType;
import com.easytask.backend.task.Task;
import com.easytask.backend.task.TaskAssignment;
import com.easytask.backend.task.TaskAssignmentRepository;
import com.easytask.backend.task.TaskRepository;
import com.easytask.backend.user.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Daily job: rules with {@code next_run_at <= now} generate a task instance per due
 * period (bounded catch-up), advance by frequency × interval, and deactivate past
 * their end date. Generated tasks behave like normal tasks (assignments + notifications).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringTaskGenerationService {

    /** Safety bound on catch-up instances per rule per run. */
    private static final int MAX_CATCH_UP = 31;

    private final RecurringTaskRuleRepository ruleRepository;
    private final RecurringTaskRuleAssigneeRepository ruleAssigneeRepository;
    private final RecurringTaskRuleExceptionRepository exceptionRepository;
    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final Clock clock;

    @Scheduled(cron = "${easytask.recurring.cron:0 0 6 * * *}")
    @Transactional
    public void generateDueTasks() {
        Instant now = clock.instant();
        List<RecurringTaskRule> dueRules = ruleRepository.findAllByActiveTrueAndNextRunAtLessThanEqual(now);
        int generated = 0;
        for (RecurringTaskRule rule : dueRules) {
            generated += generateForRule(rule, now);
        }
        if (!dueRules.isEmpty()) {
            log.info("Recurring task generation: {} rule(s) due, {} task(s) created", dueRules.size(), generated);
        }
    }

    private int generateForRule(RecurringTaskRule rule, Instant now) {
        List<AppUser> assignees = ruleAssigneeRepository.findAllByRuleId(rule.getId()).stream()
                .map(RecurringTaskRuleAssignee::getUser)
                .toList();
        int generated = 0;
        int iterations = 0;
        while (rule.isActive() && rule.getNextRunAt() != null && !rule.getNextRunAt().isAfter(now)
                && iterations < MAX_CATCH_UP) {
            iterations++;
            LocalDate runDate = rule.getNextRunAt().atZone(ZoneOffset.UTC).toLocalDate();
            if (rule.getRecurrenceEndDate() != null && runDate.isAfter(rule.getRecurrenceEndDate())) {
                rule.setActive(false);
                break;
            }
            // AF-11: a skipped run-date produces no task, but the schedule still advances.
            if (exceptionRepository.existsByRuleIdAndExceptionDate(rule.getId(), runDate)) {
                log.debug("Recurring rule {} skips {} (exception)", rule.getId(), runDate);
            } else {
                createInstance(rule, runDate, assignees);
                generated++;
            }

            LocalDate nextRun = nextRunDate(runDate, rule.getFrequency(), rule.getRecurrenceInterval());
            rule.setNextRunAt(nextRun.atStartOfDay(ZoneOffset.UTC).toInstant());
            if (rule.getRecurrenceEndDate() != null && nextRun.isAfter(rule.getRecurrenceEndDate())) {
                rule.setActive(false);
            }
        }
        return generated;
    }

    private void createInstance(RecurringTaskRule rule, LocalDate runDate, List<AppUser> assignees) {
        LocalDate dueDate = null;
        if (rule.getTemplateDueDate() != null) {
            LocalDate templateStart = rule.getTemplateStartDate() != null
                    ? rule.getTemplateStartDate() : rule.getRecurrenceStartDate();
            long offsetDays = Math.max(0, ChronoUnit.DAYS.between(templateStart, rule.getTemplateDueDate()));
            dueDate = runDate.plusDays(offsetDays);
        }
        Task task = taskRepository.save(Task.builder()
                .project(rule.getProject())
                .createdBy(rule.getCreatedBy())
                .recurringTaskRule(rule)
                .title(rule.getTitle())
                .description(rule.getDescription())
                .priority(rule.getPriority())
                .startDate(runDate)
                .dueDate(dueDate)
                .estimatedHours(rule.getEstimatedHours())
                .build());
        for (AppUser assignee : assignees) {
            taskAssignmentRepository.save(TaskAssignment.builder()
                    .task(task)
                    .assignee(assignee)
                    .assignedBy(rule.getCreatedBy())
                    .build());
        }
        activityService.log(task, rule.getCreatedBy(), ActivityEventType.TASK_CREATED, null,
                "Generated by recurring rule '%s'".formatted(rule.getTitle()));
        notificationService.notifyAllExceptActor(assignees, rule.getCreatedBy().getId(), task,
                NotificationType.TASK_ASSIGNED, "You were assigned to task '%s'".formatted(task.getTitle()));
    }

    static LocalDate nextRunDate(LocalDate current, RecurrenceFrequency frequency, int interval) {
        return switch (frequency) {
            case DAILY -> current.plusDays(interval);
            case WEEKLY -> current.plusWeeks(interval);
            case MONTHLY -> current.plusMonths(interval);
        };
    }
}
