package com.easytask.backend.task;

import com.easytask.backend.auth.AuthenticatedUser;
import com.easytask.backend.common.ConflictException;
import com.easytask.backend.common.ItemsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Daily-focus pins (P4-7, D33): private per-user bookmarks that never touch
 * the task lifecycle and write no activity or notifications.
 */
@Service
@RequiredArgsConstructor
public class TaskPinService {

    static final int MAX_PINS = 20;

    private final TaskPinRepository pinRepository;
    private final TaskAccessService taskAccessService;
    private final TaskService taskService;

    /** The caller's pinned tasks, newest pin first, excluding tasks no longer visible to them. */
    @Transactional(readOnly = true)
    public ItemsResponse<TaskListItemResponse> list(AuthenticatedUser principal) {
        List<Task> visible = new ArrayList<>();
        for (TaskPin pin : pinRepository.findAllByUserIdOrderByPinnedAtDesc(principal.id())) {
            taskAccessService.findVisibleTask(principal, pin.getTaskId()).ifPresent(visible::add);
        }
        return new ItemsResponse<>(taskService.toListItems(principal, visible));
    }

    @Transactional
    public void pin(AuthenticatedUser principal, UUID taskId) {
        taskAccessService.getVisibleTask(principal, taskId); // invisible task reads as 404
        if (pinRepository.existsByUserIdAndTaskId(principal.id(), taskId)) {
            return; // already pinned — no-op
        }
        if (pinRepository.countByUserId(principal.id()) >= MAX_PINS) {
            throw new ConflictException("You can pin at most %d tasks".formatted(MAX_PINS));
        }
        pinRepository.save(TaskPin.builder()
                .userId(principal.id())
                .taskId(taskId)
                .build());
    }

    /** No-op when the task is not pinned (or not even visible) — unpinning is idempotent. */
    @Transactional
    public void unpin(AuthenticatedUser principal, UUID taskId) {
        pinRepository.deleteByUserIdAndTaskId(principal.id(), taskId);
    }
}
