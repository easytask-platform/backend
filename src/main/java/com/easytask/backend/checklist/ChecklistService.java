package com.easytask.backend.checklist;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ForbiddenException;
import com.easytask.backend.common.ItemsResponse;
import com.easytask.backend.common.NotFoundException;
import com.easytask.backend.project.ProjectAccessService;
import com.easytask.backend.task.Task;
import com.easytask.backend.task.TaskAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Per-task checklists (P4-5, D36). Deliberately writes NO activity log
 * entries and NO notifications — checklist churn would drown the feed.
 */
@Service
@RequiredArgsConstructor
public class ChecklistService {

    private final TaskChecklistItemRepository itemRepository;
    private final TaskAccessService taskAccessService;
    private final ProjectAccessService projectAccessService;

    @Transactional(readOnly = true)
    public ItemsResponse<ChecklistItemResponse> list(AuthenticatedUser principal, UUID taskId) {
        taskAccessService.getVisibleTask(principal, taskId);
        return new ItemsResponse<>(itemRepository.findAllByTaskIdOrderByPositionAsc(taskId).stream()
                .map(ChecklistItemResponse::from)
                .toList());
    }

    @Transactional
    public ChecklistItemResponse create(AuthenticatedUser principal, UUID taskId,
                                        CreateChecklistItemRequest request) {
        Task task = taskAccessService.getVisibleTask(principal, taskId);
        projectAccessService.requireManagementRights(principal, task.getProject().getId());
        TaskChecklistItem item = itemRepository.save(TaskChecklistItem.builder()
                .task(task)
                .title(request.title())
                .position(itemRepository.maxPosition(taskId) + 1)
                .build());
        return ChecklistItemResponse.from(item);
    }

    @Transactional
    public ChecklistItemResponse update(AuthenticatedUser principal, UUID itemId,
                                        UpdateChecklistItemRequest request) {
        TaskChecklistItem item = requireVisibleItem(principal, itemId);
        if (request.title() != null || request.position() != null) {
            requireManageRights(principal, item.getTask());
        } else if (request.done() != null && !canToggleDone(principal, item.getTask())) {
            throw new ForbiddenException("Only task assignees or task managers can toggle checklist items");
        }
        if (request.title() != null) {
            item.setTitle(request.title());
        }
        if (request.position() != null) {
            item.setPosition(request.position());
        }
        if (request.done() != null) {
            item.setDone(request.done());
        }
        return ChecklistItemResponse.from(item);
    }

    @Transactional
    public void delete(AuthenticatedUser principal, UUID itemId) {
        TaskChecklistItem item = requireVisibleItem(principal, itemId);
        requireManageRights(principal, item.getTask());
        itemRepository.delete(item);
    }

    /** Out-of-scope tasks (other org / not visible) must read as 404, not as a permission hint. */
    private TaskChecklistItem requireVisibleItem(AuthenticatedUser principal, UUID itemId) {
        TaskChecklistItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new NotFoundException("Checklist item not found"));
        taskAccessService.getVisibleTask(principal, item.getTask().getId());
        return item;
    }

    /** Structural writes work like tag writes: task:manage + management rights on the project. */
    private void requireManageRights(AuthenticatedUser principal, Task task) {
        if (!principal.can("task:manage")) {
            throw new ForbiddenException("Managing checklist items requires task:manage");
        }
        projectAccessService.requireManagementRights(principal, task.getProject().getId());
    }

    private boolean canToggleDone(AuthenticatedUser principal, Task task) {
        return taskAccessService.isAssignee(task.getId(), principal.id()) || principal.can("task:manage");
    }
}
