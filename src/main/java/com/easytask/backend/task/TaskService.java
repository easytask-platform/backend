package com.easytask.backend.task;

import com.easytask.backend.activity.ActivityEventType;
import com.easytask.backend.activity.ActivityService;
import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.checklist.TaskChecklistItemRepository;
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
import com.easytask.backend.tag.Tag;
import com.easytask.backend.tag.TagRepository;
import com.easytask.backend.tag.TagSummary;
import com.easytask.backend.timeentry.TimeEntryRepository;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.AppUserRepository;
import com.easytask.backend.user.AvatarUrls;
import com.easytask.backend.role.DataScope;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
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
import java.util.Comparator;
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
    private final TagRepository tagRepository;
    private final TaskChecklistItemRepository checklistItemRepository;
    private final TaskPinRepository taskPinRepository;
    private final AppUserRepository userRepository;
    private final ProjectAccessService projectAccessService;
    private final ActivityService activityService;
    private final NotificationService notificationService;

    public record TaskFilters(String search, TaskStatus status, TaskPriority priority, UUID projectId,
                              UUID assigneeId, UUID tagId, LocalDate dueFrom, LocalDate dueTo,
                              Boolean overdue) {
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskListItemResponse> list(AuthenticatedUser principal, TaskFilters filters,
                                                   Pageable pageable) {
        return toListResponse(principal, taskRepository.findAll(taskSpecification(principal, filters), pageable));
    }

    /** Maps a page of tasks to the contract list shape, batching per-task lookups. */
    @Transactional(readOnly = true)
    public PageResponse<TaskListItemResponse> toListResponse(AuthenticatedUser principal, Page<Task> page) {
        ListContext context = listContext(principal, page.getContent());
        return PageResponse.from(page.map(task -> toListItem(task, context)));
    }

    /** Same mapping for a plain list, preserving its order (used by the focus board, D33). */
    @Transactional(readOnly = true)
    public List<TaskListItemResponse> toListItems(AuthenticatedUser principal, List<Task> tasks) {
        ListContext context = listContext(principal, tasks);
        return tasks.stream().map(task -> toListItem(task, context)).toList();
    }

    /** Batched lookups (assignees, hours, tags, checklist counts, caller's pins) for list mapping. */
    private record ListContext(Map<UUID, List<TaskAssignment>> assignments,
                               Map<UUID, BigDecimal> loggedHours,
                               Map<UUID, List<TagSummary>> tags,
                               Map<UUID, int[]> checklistCounts,
                               Set<UUID> pinnedTaskIds) {
    }

    private ListContext listContext(AuthenticatedUser principal, List<Task> tasks) {
        List<UUID> taskIds = tasks.stream().map(Task::getId).toList();
        return new ListContext(assignmentsByTask(taskIds), loggedHoursByTask(taskIds),
                tagsByTask(taskIds), checklistCountsByTask(taskIds),
                taskIds.isEmpty() ? Set.of() : taskPinRepository.findPinnedTaskIds(principal.id(), taskIds));
    }

    private TaskListItemResponse toListItem(Task task, ListContext context) {
        int[] checklist = context.checklistCounts().getOrDefault(task.getId(), new int[] {0, 0});
        return new TaskListItemResponse(
                task.getId(),
                task.getProject().getId(),
                task.getProject().getName(),
                task.getTitle(),
                task.getStatus(),
                task.getPriority(),
                task.getStartDate(),
                task.getDueDate(),
                task.getEstimatedHours(),
                context.loggedHours().getOrDefault(task.getId(), BigDecimal.ZERO),
                isOverdue(task),
                context.assignments().getOrDefault(task.getId(), List.of()).stream()
                        .map(a -> new TaskListItemResponse.AssigneeSummary(
                                a.getAssignee().getId(), a.getAssignee().getFullName(),
                                AvatarUrls.of(a.getAssignee())))
                        .toList(),
                context.tags().getOrDefault(task.getId(), List.of()),
                task.isBlocked(),
                task.getBlockedReason(),
                checklist[0],
                checklist[1],
                context.pinnedTaskIds().contains(task.getId()));
    }

    @Transactional(readOnly = true)
    public TaskDetailResponse get(AuthenticatedUser principal, UUID taskId) {
        Task task = requireVisibleTask(principal, taskId);
        return toDetail(principal, task);
    }

    @Transactional
    public TaskDetailResponse create(AuthenticatedUser principal, CreateTaskRequest request) {
        Project project = projectAccessService.getVisibleProject(principal, request.projectId());
        projectAccessService.requireManagementRights(principal, request.projectId());
        validateDates(request.startDate(), request.dueDate());
        List<Tag> tags = resolveTags(request.projectId(), request.tagIds());

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
        for (Tag tag : tags) {
            task.getTags().add(tag);
            activityService.log(task, actor, ActivityEventType.TAG_ADDED, null, tag.getName());
        }
        activityService.log(task, actor, ActivityEventType.TASK_CREATED, null, task.getTitle());
        notificationService.notifyAllExceptActor(assignees, principal.id(), task, NotificationType.TASK_ASSIGNED,
                "You were assigned to task '%s'".formatted(task.getTitle()));
        return toDetail(principal, task);
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

        if (request.tagIds() != null) {
            applyTags(task, actor, request.tagIds());
        }

        activityService.log(task, actor, ActivityEventType.TASK_UPDATED, null, task.getTitle());
        notificationService.notifyAllExceptActor(updatedNotificationTargets, principal.id(), task,
                NotificationType.TASK_UPDATED, "Task '%s' was updated".formatted(task.getTitle()));
        return toDetail(principal, task);
    }

    /**
     * Blocked/waiting flag (P4-6, D37): assignees or task:manage may toggle;
     * blocking requires a reason and never touches the status machine.
     * Blocking an already-blocked task just updates the reason.
     */
    @Transactional
    public TaskDetailResponse setBlocked(AuthenticatedUser principal, UUID taskId,
                                         UpdateTaskBlockedRequest request) {
        Task task = requireVisibleTask(principal, taskId);
        if (!taskAccessService.isAssignee(taskId, principal.id()) && !principal.can("task:manage")) {
            throw new ForbiddenException("Only task assignees or task managers can change the blocked flag");
        }
        AppUser actor = userRepository.getReferenceById(principal.id());
        if (request.blocked()) {
            String reason = request.reason() == null ? null : request.reason().trim();
            if (reason == null || reason.isEmpty()) {
                throw new ValidationException("reason", "A reason is required when blocking a task");
            }
            task.setBlocked(true);
            task.setBlockedReason(reason);
            activityService.log(task, actor, ActivityEventType.TASK_BLOCKED, null, reason);
            notificationService.notifyAllExceptActor(taskAccessService.interestedUsers(task),
                    principal.id(), task, NotificationType.TASK_BLOCKED,
                    "Task '%s' was blocked: %s".formatted(task.getTitle(), reason));
        } else if (task.isBlocked()) {
            String oldReason = task.getBlockedReason();
            task.setBlocked(false);
            task.setBlockedReason(null);
            activityService.log(task, actor, ActivityEventType.TASK_UNBLOCKED, oldReason, null);
            // no notification on unblock (D37)
        }
        return toDetail(principal, task);
    }

    @Transactional
    public TaskDetailResponse changeStatus(AuthenticatedUser principal, UUID taskId,
                                           UpdateTaskStatusRequest request) {
        Task task = requireVisibleTask(principal, taskId);
        requireStatusChangeRights(principal, task);

        TaskStatus from = task.getStatus();
        TaskStatus to = request.status();
        if (!TaskStatusTransitions.isAllowed(principal.permissions(), from, to)) {
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
        return toDetail(principal, task);
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

    /**
     * ASSIGNED scope may only move own assigned tasks; MANAGED scope needs
     * management rights on the project; ORGANIZATION scope is unrestricted.
     */
    private void requireStatusChangeRights(AuthenticatedUser principal, Task task) {
        switch (principal.scope()) {
            case ORGANIZATION -> { /* full access */ }
            case MANAGED -> projectAccessService.requireManagementRights(principal, task.getProject().getId());
            case ASSIGNED -> {
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

    /** Loads the requested tags, rejecting ids that do not exist in the task's project. */
    private List<Tag> resolveTags(UUID projectId, List<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>(tagIds);
        List<Tag> tags = tagRepository.findAllById(ids);
        if (tags.size() != ids.size()
                || tags.stream().anyMatch(tag -> !tag.getProject().getId().equals(projectId))) {
            throw new ValidationException("tagIds", "Every tag must belong to the task's project");
        }
        return tags;
    }

    /** Reconciles the task's tags with the requested set, logging TAG_ADDED / TAG_REMOVED. */
    private void applyTags(Task task, AppUser actor, List<UUID> tagIds) {
        List<Tag> requested = resolveTags(task.getProject().getId(), tagIds);
        Set<UUID> requestedIds = new HashSet<>();
        requested.forEach(tag -> requestedIds.add(tag.getId()));

        for (Tag tag : List.copyOf(task.getTags())) {
            if (!requestedIds.contains(tag.getId())) {
                task.getTags().remove(tag);
                activityService.log(task, actor, ActivityEventType.TAG_REMOVED, tag.getName(), null);
            }
        }
        Set<UUID> currentIds = new HashSet<>();
        task.getTags().forEach(tag -> currentIds.add(tag.getId()));
        for (Tag tag : requested) {
            if (!currentIds.contains(tag.getId())) {
                task.getTags().add(tag);
                activityService.log(task, actor, ActivityEventType.TAG_ADDED, null, tag.getName());
            }
        }
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

    private TaskDetailResponse toDetail(AuthenticatedUser principal, Task task) {
        List<TaskDetailResponse.AssigneeDetail> assignees = taskAssignmentRepository
                .findAllByTaskId(task.getId()).stream()
                .map(a -> new TaskDetailResponse.AssigneeDetail(
                        a.getAssignee().getId(), a.getAssignee().getFullName(), a.getAssignee().getEmail(),
                        AvatarUrls.of(a.getAssignee())))
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
                task.getTags().stream()
                        .sorted(Comparator.comparing(Tag::getName))
                        .map(TagSummary::from)
                        .toList(),
                task.isBlocked(),
                task.getBlockedReason(),
                (int) checklistItemRepository.countByTaskIdAndDoneTrue(task.getId()),
                (int) checklistItemRepository.countByTaskId(task.getId()),
                taskPinRepository.existsByUserIdAndTaskId(principal.id(), task.getId()),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    private Map<UUID, int[]> checklistCountsByTask(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, int[]> counts = new HashMap<>();
        for (Object[] row : checklistItemRepository.countsByTask(taskIds)) {
            counts.put((UUID) row[0], new int[] {((Number) row[1]).intValue(), ((Number) row[2]).intValue()});
        }
        return counts;
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

    private Map<UUID, List<TagSummary>> tagsByTask(List<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<TagSummary>> byTask = new HashMap<>();
        for (Object[] row : tagRepository.findTaskTagPairs(taskIds)) {
            byTask.computeIfAbsent((UUID) row[0], id -> new ArrayList<>()).add(TagSummary.from((Tag) row[1]));
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
            if (filters.tagId() != null) {
                Subquery<UUID> tagged = query.subquery(UUID.class);
                Root<Task> taggedTask = tagged.from(Task.class);
                Join<Task, Tag> tag = taggedTask.join("tags");
                tagged.select(taggedTask.get("id"))
                        .where(cb.equal(taggedTask.get("id"), root.get("id")),
                                cb.equal(tag.get("id"), filters.tagId()));
                predicates.add(cb.exists(tagged));
            }
            if (filters.overdue() != null) {
                Predicate pastDue = cb.and(
                        cb.isNotNull(root.get("dueDate")),
                        cb.lessThan(root.get("dueDate"), LocalDate.now()),
                        root.get("status").in(List.of(TaskStatus.APPROVED, TaskStatus.CANCELLED)).not());
                predicates.add(filters.overdue() ? pastDue : cb.not(pastDue));
            }
            if (principal.scope() != DataScope.ORGANIZATION) {
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
