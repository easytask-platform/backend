package com.easytask.backend.recurring;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.NotFoundException;
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
import com.easytask.backend.user.UserRole;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final RecurringTaskRuleRepository ruleRepository;
    private final RecurringTaskRuleAssigneeRepository ruleAssigneeRepository;
    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final AppUserRepository userRepository;
    private final ProjectAccessService projectAccessService;

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
        if (principal.role() != UserRole.ORGANIZATION_ADMIN
                && !projectAccessService.isVisible(principal, rule.getProject().getId())) {
            throw new NotFoundException("Recurring task rule not found");
        }
        return taskService.toListResponse(taskRepository.findAllByRecurringTaskRuleId(ruleId, pageable));
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
            if (principal.role() != UserRole.ORGANIZATION_ADMIN) {
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
