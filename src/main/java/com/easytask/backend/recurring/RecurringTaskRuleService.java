package com.easytask.backend.recurring;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.role.DataScope;
import com.easytask.backend.common.PageResponse;
import com.easytask.backend.common.ValidationException;
import com.easytask.backend.project.Project;
import com.easytask.backend.project.ProjectAccessService;
import com.easytask.backend.project.ProjectMember;
import com.easytask.backend.task.TaskListItemResponse;
import com.easytask.backend.task.TaskRepository;
import com.easytask.backend.task.TaskService;
import com.easytask.backend.task.TaskPriority;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecurringTaskRuleService {

    /** Safety cap on the occurrence list length. */
    static final int MAX_OCCURRENCES = 50;

    private final RecurringTaskRuleRepository ruleRepository;
    private final RecurringTaskRuleAssigneeRepository ruleAssigneeRepository;
    private final RecurringTaskRuleExceptionRepository exceptionRepository;
    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final AppUserRepository userRepository;
    private final ProjectAccessService projectAccessService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<RecurringTaskRuleResponse> list(AuthenticatedUser principal, UUID projectId,
                                                        RecurrenceFrequency frequency, Pageable pageable) {
        Page<RecurringTaskRule> page = ruleRepository.findAll(
                ruleSpecification(principal, projectId, frequency), pageable);
        Map<UUID, List<UUID>> assigneesByRule = assigneeIdsByRule(page.getContent());
        return PageResponse.from(page.map(rule -> RecurringTaskRuleResponse.from(rule,
                assigneesByRule.getOrDefault(rule.getId(), List.of()))));
    }

    @Transactional
    public RecurringTaskRuleResponse create(AuthenticatedUser principal, CreateRecurringTaskRuleRequest request) {
        Project project = projectAccessService.getVisibleProject(principal, request.projectId());
        projectAccessService.requireManagementRights(principal, request.projectId());
        validateDates(request);

        RecurringTaskRule rule = ruleRepository.save(RecurringTaskRule.builder()
                .project(project)
                .createdBy(userRepository.getReferenceById(principal.id()))
                .title(request.title())
                .description(request.description())
                .priority(request.priority() == null ? TaskPriority.MEDIUM : request.priority())
                .templateStartDate(request.startDate())
                .templateDueDate(request.dueDate())
                .estimatedHours(request.estimatedHours())
                .frequency(request.frequency())
                .recurrenceInterval(request.interval() == null ? 1 : request.interval())
                .recurrenceStartDate(request.recurrenceStartDate())
                .recurrenceEndDate(request.recurrenceEndDate())
                .nextRunAt(request.recurrenceStartDate().atStartOfDay(ZoneOffset.UTC).toInstant())
                .build());

        List<UUID> assigneeIds = List.of();
        if (request.assigneeIds() != null && !request.assigneeIds().isEmpty()) {
            Set<UUID> ids = new LinkedHashSet<>(request.assigneeIds());
            List<AppUser> users = userRepository.findAllByIdInAndOrganizationId(ids, principal.organizationId());
            if (users.size() != ids.size()) {
                throw new NotFoundException("Assignee not found");
            }
            for (AppUser user : users) {
                ruleAssigneeRepository.save(RecurringTaskRuleAssignee.builder()
                        .rule(rule)
                        .user(user)
                        .build());
            }
            assigneeIds = users.stream().map(AppUser::getId).toList();
        }
        return RecurringTaskRuleResponse.from(rule, assigneeIds);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskListItemResponse> generatedTasks(AuthenticatedUser principal, UUID ruleId,
                                                             Pageable pageable) {
        RecurringTaskRule rule = ruleRepository.findByIdAndProjectOrganizationId(ruleId,
                        principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Recurring task rule not found"));
        if (principal.scope() != DataScope.ORGANIZATION
                && !projectAccessService.isVisible(principal, rule.getProject().getId())) {
            throw new NotFoundException("Recurring task rule not found");
        }
        return taskService.toListResponse(principal, taskRepository.findAllByRecurringTaskRuleId(ruleId, pageable));
    }

    // --- Occurrences + exceptions (P4-10, D41) --------------------------------

    /** The next {@code count} upcoming run dates from {@code nextRunAt}, each flagged when skipped. */
    @Transactional(readOnly = true)
    public ItemsResponse<OccurrenceResponse> occurrences(AuthenticatedUser principal, UUID ruleId, int count) {
        RecurringTaskRule rule = requireManagedRule(principal, ruleId);
        Set<LocalDate> exceptions = exceptionRepository.exceptionDates(ruleId);
        List<OccurrenceResponse> items = upcomingRunDates(rule, count).stream()
                .map(date -> new OccurrenceResponse(date, exceptions.contains(date)))
                .toList();
        return new ItemsResponse<>(items);
    }

    /** Skip a future, not-yet-generated run date. 409 if the date is in the past or not an actual occurrence. */
    @Transactional
    public void addException(AuthenticatedUser principal, UUID ruleId, LocalDate date) {
        RecurringTaskRule rule = requireManagedRule(principal, ruleId);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!date.isAfter(today.minusDays(1))) {
            // date in the past (or today, already due) — cannot be skipped
            throw new ConflictException("Only a future run date can be skipped");
        }
        if (!isScheduledRunDate(rule, date)) {
            throw new ConflictException("That date is not a scheduled run date for this rule");
        }
        if (exceptionRepository.existsByRuleIdAndExceptionDate(ruleId, date)) {
            return; // idempotent
        }
        exceptionRepository.save(RecurringTaskRuleException.builder()
                .rule(rule)
                .exceptionDate(date)
                .createdBy(userRepository.getReferenceById(principal.id()))
                .build());
    }

    @Transactional
    public void removeException(AuthenticatedUser principal, UUID ruleId, LocalDate date) {
        requireManagedRule(principal, ruleId);
        exceptionRepository.findByRuleIdAndExceptionDate(ruleId, date)
                .ifPresent(exceptionRepository::delete);
    }

    private RecurringTaskRule requireManagedRule(AuthenticatedUser principal, UUID ruleId) {
        RecurringTaskRule rule = ruleRepository.findByIdAndProjectOrganizationId(ruleId,
                        principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Recurring task rule not found"));
        if (principal.scope() != DataScope.ORGANIZATION
                && !projectAccessService.isVisible(principal, rule.getProject().getId())) {
            throw new NotFoundException("Recurring task rule not found");
        }
        return rule;
    }

    /** Computes the upcoming run dates from {@code nextRunAt}, honouring end date and interval. */
    private List<LocalDate> upcomingRunDates(RecurringTaskRule rule, int count) {
        int capped = Math.min(Math.max(1, count), MAX_OCCURRENCES);
        List<LocalDate> dates = new ArrayList<>();
        if (!rule.isActive() || rule.getNextRunAt() == null) {
            return dates;
        }
        LocalDate runDate = rule.getNextRunAt().atZone(ZoneOffset.UTC).toLocalDate();
        while (dates.size() < capped) {
            if (rule.getRecurrenceEndDate() != null && runDate.isAfter(rule.getRecurrenceEndDate())) {
                break;
            }
            dates.add(runDate);
            runDate = RecurringTaskGenerationService.nextRunDate(runDate, rule.getFrequency(),
                    rule.getRecurrenceInterval());
        }
        return dates;
    }

    /** A date is a scheduled run date if it appears in the (bounded) upcoming occurrence stream. */
    private boolean isScheduledRunDate(RecurringTaskRule rule, LocalDate date) {
        if (!rule.isActive() || rule.getNextRunAt() == null) {
            return false;
        }
        if (rule.getRecurrenceEndDate() != null && date.isAfter(rule.getRecurrenceEndDate())) {
            return false;
        }
        LocalDate runDate = rule.getNextRunAt().atZone(ZoneOffset.UTC).toLocalDate();
        if (date.isBefore(runDate)) {
            return false;
        }
        // Walk the schedule until we reach or pass the target date (bounded by MAX_OCCURRENCES * cushion).
        for (int i = 0; i < MAX_OCCURRENCES * 12 && !runDate.isAfter(date); i++) {
            if (runDate.equals(date)) {
                return true;
            }
            runDate = RecurringTaskGenerationService.nextRunDate(runDate, rule.getFrequency(),
                    rule.getRecurrenceInterval());
        }
        return false;
    }

    private void validateDates(CreateRecurringTaskRuleRequest request) {
        if (request.startDate() != null && request.dueDate() != null
                && request.dueDate().isBefore(request.startDate())) {
            throw new ValidationException("dueDate", "Due date must be on or after the start date");
        }
        if (request.recurrenceEndDate() != null
                && request.recurrenceEndDate().isBefore(request.recurrenceStartDate())) {
            throw new ValidationException("recurrenceEndDate",
                    "Recurrence end date must be on or after the recurrence start date");
        }
    }

    private Map<UUID, List<UUID>> assigneeIdsByRule(List<RecurringTaskRule> rules) {
        Map<UUID, List<UUID>> byRule = new HashMap<>();
        for (RecurringTaskRule rule : rules) {
            byRule.put(rule.getId(), ruleAssigneeRepository.findAllByRuleId(rule.getId()).stream()
                    .map(assignee -> assignee.getUser().getId())
                    .toList());
        }
        return byRule;
    }

    private Specification<RecurringTaskRule> ruleSpecification(AuthenticatedUser principal, UUID projectId,
                                                               RecurrenceFrequency frequency) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("project").get("organization").get("id"),
                    principal.organizationId()));
            if (projectId != null) {
                predicates.add(cb.equal(root.get("project").get("id"), projectId));
            }
            if (frequency != null) {
                predicates.add(cb.equal(root.get("frequency"), frequency));
            }
            if (principal.scope() != DataScope.ORGANIZATION) {
                Subquery<UUID> membership = query.subquery(UUID.class);
                Root<ProjectMember> pm = membership.from(ProjectMember.class);
                membership.select(pm.get("id"))
                        .where(cb.equal(pm.get("project"), root.get("project")),
                                cb.equal(pm.get("user").get("id"), principal.id()));
                predicates.add(cb.exists(membership));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
