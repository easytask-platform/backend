package com.easytask.backend.task;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.project.ProjectAccessService;
import com.easytask.backend.role.DataScope;
import com.easytask.backend.user.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Central task scoping rules, shared by task, comment, attachment and time-entry features. */
@Service
@RequiredArgsConstructor
public class TaskAccessService {

    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final ProjectAccessService projectAccessService;

    /** Visible = own org AND (admin, assignee, or member of the task's project). Otherwise 404. */
    public Task getVisibleTask(AuthenticatedUser principal, UUID taskId) {
        return findVisibleTask(principal, taskId)
                .orElseThrow(() -> new NotFoundException("Task not found"));
    }

    /** Same rule as {@link #getVisibleTask}, empty instead of 404 (used to filter focus pins, D33). */
    public Optional<Task> findVisibleTask(AuthenticatedUser principal, UUID taskId) {
        return taskRepository.findByIdAndProjectOrganizationId(taskId, principal.organizationId())
                .filter(task -> principal.scope() == DataScope.ORGANIZATION
                        || taskAssignmentRepository.existsByTaskIdAndAssigneeId(taskId, principal.id())
                        || projectAccessService.isVisible(principal, task.getProject().getId()));
    }

    public boolean isAssignee(UUID taskId, UUID userId) {
        return taskAssignmentRepository.existsByTaskIdAndAssigneeId(taskId, userId);
    }

    /**
     * Whether an arbitrary same-org user could open the task — the same rule as
     * {@link #getVisibleTask} applied to someone other than the caller (used to
     * validate @mentions, D35). Callers must already have scoped the user to the
     * task's organization.
     */
    public boolean canSee(AppUser user, Task task) {
        return taskAssignmentRepository.existsByTaskIdAndAssigneeId(task.getId(), user.getId())
                || projectAccessService.isVisibleTo(user.getId(), user.getRole().getDataScope(),
                        task.getProject().getId());
    }

    /** Users notified about task events: assignees plus the task creator (deduplicated). */
    public List<AppUser> interestedUsers(Task task) {
        Set<UUID> seen = new LinkedHashSet<>();
        List<AppUser> users = new ArrayList<>();
        for (TaskAssignment assignment : taskAssignmentRepository.findAllByTaskId(task.getId())) {
            if (seen.add(assignment.getAssignee().getId())) {
                users.add(assignment.getAssignee());
            }
        }
        if (seen.add(task.getCreatedBy().getId())) {
            users.add(task.getCreatedBy());
        }
        return users;
    }
}
