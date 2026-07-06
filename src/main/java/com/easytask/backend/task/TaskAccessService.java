package com.easytask.backend.task;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.project.ProjectAccessService;
import com.easytask.backend.user.AppUser;
import com.easytask.backend.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        Task task = taskRepository.findByIdAndProjectOrganizationId(taskId, principal.organizationId())
                .orElseThrow(() -> new NotFoundException("Task not found"));
        if (principal.role() != UserRole.ORGANIZATION_ADMIN
                && !taskAssignmentRepository.existsByTaskIdAndAssigneeId(taskId, principal.id())
                && !projectAccessService.isVisible(principal, task.getProject().getId())) {
            throw new NotFoundException("Task not found");
        }
        return task;
    }

    public boolean isAssignee(UUID taskId, UUID userId) {
        return taskAssignmentRepository.existsByTaskIdAndAssigneeId(taskId, userId);
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
