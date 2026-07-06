package com.easytask.backend.task;

import com.easytask.backend.activity.ActivityEventType;
import com.easytask.backend.activity.ActivityService;
import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.ForbiddenException;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.common.PageResponse;
import com.easytask.backend.common.ValidationException;
import com.easytask.backend.notification.NotificationService;
import com.easytask.backend.notification.NotificationType;
import com.easytask.backend.project.Project;
import com.easytask.backend.project.ProjectAccessService;
import com.easytask.backend.project.ProjectMember;
import com.easytask.backend.timeentry.TimeEntryRepository;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import com.easytask.backend.user.UserRole;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final TaskAccessService taskAccessService;
    private final TimeEntryRepository timeEntryRepository;
    private final AppUserRepository userRepository;
    private final ProjectAccessService projectAccessService;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    public record TaskFilters(String search, TaskStatus status, TaskPriority priority, UUID projectId,
                              UUID assigneeId, LocalDate dueFrom, LocalDate dueTo, Boolean overdue) {
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskListItemResponse> list(AuthenticatedUser principal, TaskFilters filters,
                                                   Pageable pageable) {
        return toListResponse(taskRepository.findAll(taskSpecification(principal, filters), pageable));
    }

    /** Maps a page of tasks to the contract list shape, batching assignees and logged hours. */
    @Transactional(readOnly = true)
    public PageResponse<TaskListItemResponse> toListResponse(Page<Task> page) {
        List<UUID> taskIds = page.getContent().stream().map(Task::getId).toList();
        Map<UUID, List<TaskAssignment>> assignments = assignmentsByTask(taskIds);
        Map<UUID, BigDecimal> loggedHours = loggedHoursByTask(taskIds);
        return PageResponse.from(page.map(task -> new TaskListItemResponse(
                task.getId(),
                task.getProject().getId(),
                task.getProject().getName(),
                task.getTitle(),
                task.getStatus(),
                task.getPriority(),
                task.getStartDate(),
                task.getDueDate(),
                task.getEstimatedHours(),
                loggedHours.getOrDefault(task.getId(), BigDecimal.ZERO),
                isOverdue(task),
                assignments.getOrDefault(task.getId(), List.of()).stream()
                        .map(a -> new TaskListItemResponse.AssigneeSummary(
                                a.getAssignee().getId(), a.getAssignee().getFullName()))
                        .toList())));
    }

    @Transactional(readOnly = true)
    public TaskDetailResponse get(AuthenticatedUser principal, UUID taskId) {
        Task task = requireVisibleTask(principal, taskId);
        return toDetail(task);
    }

    @Transactional
    public TaskDetailResponse create(AuthenticatedUser principal, CreateTaskRequest request) {
        Project project = projectAccessService.getVisibleProject(principal, request.projectId());
        projectAccessService.requireManagementRights(principal, request.projectId());
        validateDates(request.startDate(), request.dueDate());

        AppUser actor = userRepository.getReferenceById(principal.id());
        Task task = taskRepository.save(Task.builder()
                .project(project)
                .createdBy(actor)
                .title(request.title())
                .description(request.description())
                .priority(request.priority() == null ? TaskPriority.MEDIUM : request.priority())
                .startDate(request.startDate())
                .dueDate(request.dueDate())
                .estimatedHours(request.estimatedHours())
                .build());

        List<AppUser> assignees = resolveAssignees(principal, request.assigneeIds());
        for (AppUser assignee : assignees) {
            taskAssignmentRepository.save(TaskAssignment.builder()
                    .task(task)
                    .assignee(assignee)
                    .assignedBy(actor)
                    .build());
            activityService.log(task, actor, ActivityEventType.ASSIGNEE_ADDED, null, assignee.getFullName());
        }
        activityService.log(task, actor, ActivityEventType.TASK_CREATED, null, task.getTitle());
        notificationService.notifyAllExceptActor(assignees, principal.id(), task, NotificationType.TASK_ASSIGNED,
                "You were assigned to task '%s'".formatted(task.getTitle()));
        return toDetail(task);
    }

    @Transactional
    public TaskDetailResponse update(AuthenticatedUser principal, UUID taskId, UpdateTaskRequest request) {
        Task task = requireVisibleTask(principal, taskId);
        projectAccessService.requireManagementRights(principal, task.getProject().getId());
        if (task.getStatus() == TaskStatus.APPROVED || task.getStatus() == TaskStatus.CANCELLED) {
            throw new ConflictException("Approved or cancelled tasks cannot be edited");
        }
        LocalDate newStart = request.startDate() != null ? request.startDate() : task.getStartDate();
        LocalDate newDue = request.dueDate() != null ? request.dueDate() : task.getDueDate();
        validateDates(newStart, newDue);

        if (request.title() != null) {
            task.setTitle(request.title());
        }
        if (request.description() != null) {
            task.setDescription(request.description());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        task.setStartDate(newStart);
        task.setDueDate(newDue);
        if (request.estimatedHours() != null) {
            task.setEstimatedHours(request.estimatedHours());
        }

        AppUser actor = userRepository.getReferenceById(principal.id());
        Set<AppUser> updatedNotificationTargets = new HashSet<>();
        List<TaskAssignment> currentAssignments = taskAssignmentRepository.findAllByTaskId(taskId);

        if (request.assigneeIds() != null) {
            Set<UUID> requestedIds = new LinkedHashSet<>(request.assigneeIds());
            List<AppUser> requestedUsers = resolveAssignees(principal, request.assigneeIds());

            for (TaskAssignment assignment : currentAssignments) {
                if (!requestedIds.contains(assignment.getAssignee().getId())) {
                    taskAssignmentRepository.delete(assignment);
                    activityService.log(task, actor, ActivityEventType.ASSIGNEE_REMOVED,
                            assignment.getAssignee().getFullName(), null);
                } else {
                    updatedNotificationTargets.add(assignment.getAssignee());
                }
            }
            Set<UUID> currentIds = new HashSet<>();
            currentAssignments.forEach(a -> currentIds.add(a.getAssignee().getId()));
            List<AppUser> added = requestedUsers.stream()
                    .filter(user -> !currentIds.contains(user.getId()))
                    .toList();
            for (AppUser assignee : added) {
                taskAssignmentRepository.save(TaskAssignment.builder()
                        .task(task)
                        .assignee(assignee)
                        .assignedBy(actor)
                        .build());
                activityService.log(task, actor, ActivityEventType.ASSIGNEE_ADDED, null, assignee.getFullName());
            }
            notificationService.notifyAllExceptActor(added, principal.id(), task, NotificationType.TASK_ASSIGNED,
                    "You were assigned to task '%s'".formatted(task.getTitle()));
        } else {
            currentAssignments.forEach(a -> updatedNotificationTargets.add(a.getAssignee()));
        }

        activityService.log(task, actor, ActivityEventType.TASK_UPDATED, null, task.getTitle());
        notificationService.notifyAllExceptActor(updatedNotificationTargets, principal.id(), task,
                NotificationType.TASK_UPDATED, "Task '%s' was updated".formatted(task.getTitle()));
        return toDetail(task);
    }

    @Transactional
    public TaskDetailResponse changeStatus(AuthenticatedUser principal, UUID taskId,
                                           UpdateTaskStatusRequest request) {
        Task task = requireVisibleTask(principal, taskId);
        requireStatusChangeRights(principal, task);

        TaskStatus from = task.getStatus();
        TaskStatus to = request.status();
        if (!TaskStatusTransitions.isAllowed(principal.role(), from, to)) {
            throw new ConflictException("Cannot transition task from %s to %s".formatted(from, to));
        }

        AppUser actor = userRepository.getReferenceById(principal.id());
        task.setStatus(to);
        if (to == TaskStatus.APPROVED) {
            task.setApprovedBy(actor);
            task.setApprovedAt(Instant.now());
            activityService.log(task, actor, ActivityEventType.TASK_APPROVED, from.name(), to.name());
        } else {
            activityService.log(task, actor, ActivityEventType.STATUS_CHANGED, from.name(), to.name());
        }

        List<AppUser> assignees = taskAssignmentRepository.findAllByTaskId(taskId).stream()
                .map(TaskAssignment::getAssignee)
                .toList();
        notificationService.notifyAllExceptActor(assignees, principal.id(), task, notificationTypeFor(to),
                statusChangeMessage(task, to));
        return toDetail(task);
    }

    private static NotificationType notificationTypeFor(TaskStatus to) {
        return switch (to) {
            case APPROVED -> NotificationType.TASK_APPROVED;
            case REOPENED -> NotificationType.TASK_REOPENED;
            case CANCELLED -> NotificationType.TASK_CANCELLED;
            default -> NotificationType.TASK_UPDATED;
        };
    }

    private static String statusChangeMessage(Task task, TaskStatus to) {
        return switch (to) {
            case APPROVED -> "Task '%s' was approved".formatted(task.getTitle());
            case REOPENED -> "Task '%s' was reopened".formatted(task.getTitle());
            case CANCELLED -> "Task '%s' was cancelled".formatted(task.getTitle());
            default -> "Task '%s' moved to %s".formatted(task.getTitle(), to);
        };
    }

    /** Employees may only move their own assigned tasks; managers need management rights on the project. */
    private void requireStatusChangeRights(AuthenticatedUser principal, Task task) {
        switch (principal.role()) {
            case ORGANIZATION_ADMIN -> { /* full access */ }
            case MANAGER -> projectAccessService.requireManagementRights(principal, task.getProject().getId());
            case EMPLOYEE -> {
                if (!taskAssignmentRepository.existsByTaskIdAndAssigneeId(task.getId(), principal.id())) {
                    throw new ForbiddenException("You can only update tasks assigned to you");
                }
            }
        }
    }

    private Task requireVisibleTask(AuthenticatedUser principal, UUID taskId) {
        return taskAccessService.getVisibleTask(principal, taskId);
    }

    private List<AppUser> resolveAssignees(AuthenticatedUser principal, List<UUID> assigneeIds) {
        if (assigneeIds == null || assigneeIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>(assigneeIds);
        List<AppUser> users = userRepository.findAllByIdInAndOrganizationId(ids, principal.organizationId());
        if (users.size() != ids.size()) {
            throw new NotFoundException("Assignee not found");
        }
        return users;
    }

    private void validateDates(LocalDate startDate, LocalDate dueDate) {
        if (startDate != null && dueDate != null && dueDate.isBefore(startDate)) {
            throw new ValidationException("dueDate", "Due date must be on or after the start date");
        }
    }

    private static boolean isOverdue(Task task) {
        return task.getDueDate() != null
                && task.getDueDate().isBefore(LocalDate.now())
                && task.getStatus() != TaskStatus.APPROVED
                && task.getStatus() != TaskStatus.CANCELLED;
    }

    private TaskDetailResponse toDetail(Task task) {
        List<TaskDetailResponse.AssigneeDetail> assignees = taskAssignmentRepository
                .findAllByTaskId(task.getId()).stream()
                .map(a -> new TaskDetailResponse.AssigneeDetail(
                        a.getAssignee().getId(), a.getAssignee().getFullName(), a.getAssignee().getEmail()))
                .toList();
        return new TaskDetailResponse(
                task.getId(),
                task.getProject().getId(),
                task.getProject().getName(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority(),
                task.getStartDate(),
                task.getDueDate(),
                task.getEstimatedHours(),
                timeEntryRepository.totalHoursForTask(task.getId()),
                isOverdue(task),
                assignees,
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    private Map<UUID, List<TaskAssignment>> assignmentsByTask(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<TaskAssignment>> byTask = new HashMap<>();
        for (TaskAssignment assignment : taskAssignmentRepository.findAllByTaskIdIn(taskIds)) {
            byTask.computeIfAbsent(assignment.getTask().getId(), id -> new ArrayList<>()).add(assignment);
        }
        return byTask;
    }

    private Map<UUID, BigDecimal> loggedHoursByTask(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BigDecimal> hours = new HashMap<>();
        for (Object[] row : timeEntryRepository.totalHoursForTasks(taskIds)) {
            hours.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return hours;
    }

    private Specification<Task> taskSpecification(AuthenticatedUser principal, TaskFilters filters) {
        return (root, query, cb) -> {
            if (!Long.class.equals(query.getResultType()) && !long.class.equals(query.getResultType())) {
                root.fetch("project");
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("project").get("organization").get("id"),
                    principal.organizationId()));
            if (filters.status() != null) {
                predicates.add(cb.equal(root.get("status"), filters.status()));
            }
            if (filters.priority() != null) {
                predicates.add(cb.equal(root.get("priority"), filters.priority()));
            }
            if (filters.projectId() != null) {
                predicates.add(cb.equal(root.get("project").get("id"), filters.projectId()));
            }
            if (filters.dueFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("dueDate"), filters.dueFrom()));
            }
            if (filters.dueTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("dueDate"), filters.dueTo()));
            }
            if (filters.search() != null && !filters.search().isBlank()) {
                String like = "%" + filters.search().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("description")), like)));
            }
            if (filters.assigneeId() != null) {
                predicates.add(cb.exists(assignmentSubquery(root, query, cb, filters.assigneeId())));
            }
            if (filters.overdue() != null) {
                Predicate pastDue = cb.and(
                        cb.isNotNull(root.get("dueDate")),
                        cb.lessThan(root.get("dueDate"), LocalDate.now()),
                        root.get("status").in(List.of(TaskStatus.APPROVED, TaskStatus.CANCELLED)).not());
                predicates.add(filters.overdue() ? pastDue : cb.not(pastDue));
            }
            if (principal.role() != UserRole.ORGANIZATION_ADMIN) {
                Subquery<UUID> membership = query.subquery(UUID.class);
                Root<ProjectMember> pm = membership.from(ProjectMember.class);
                membership.select(pm.get("id"))
                        .where(cb.equal(pm.get("project"), root.get("project")),
                                cb.equal(pm.get("user").get("id"), principal.id()));
                predicates.add(cb.or(
                        cb.exists(assignmentSubquery(root, query, cb, principal.id())),
                        cb.exists(membership)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Subquery<UUID> assignmentSubquery(Root<Task> root, AbstractQuery<?> query, CriteriaBuilder cb,
                                              UUID assigneeId) {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<TaskAssignment> ta = subquery.from(TaskAssignment.class);
        subquery.select(ta.get("id"))
                .where(cb.equal(ta.get("task"), root),
                        cb.equal(ta.get("assignee").get("id"), assigneeId));
        return subquery;
    }
}
