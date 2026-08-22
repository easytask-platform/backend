package com.easytask.backend.task;

import com.easytask.backend.role.PermissionCode;

import java.util.Set;

import static com.easytask.backend.task.TaskStatus.APPROVED;
import static com.easytask.backend.task.TaskStatus.CANCELLED;
import static com.easytask.backend.task.TaskStatus.IN_PROGRESS;
import static com.easytask.backend.task.TaskStatus.IN_REVIEW;
import static com.easytask.backend.task.TaskStatus.REOPENED;
import static com.easytask.backend.task.TaskStatus.TO_DO;

/**
 * Contract transition matrix, keyed by permissions since Phase 2:
 * task:manage  — full override: move a task to ANY other status, in either
 *                direction, from any current status (managers, admins).
 * task:execute — TO_DO→IN_PROGRESS, IN_PROGRESS→IN_REVIEW, REOPENED→IN_PROGRESS|IN_REVIEW.
 * task:review  — IN_REVIEW→APPROVED|REOPENED.
 * task:cancel  — any non-approved, non-cancelled task →CANCELLED.
 */
public final class TaskStatusTransitions {

    private TaskStatusTransitions() {
    }

    public static boolean isAllowed(Set<String> permissions, TaskStatus from, TaskStatus to) {
        // A move to the same status is a no-op, never a valid transition.
        if (from == to) {
            return false;
        }
        // task:manage holders (managers, admins) may set any status freely —
        // forward or backward, including into/out of APPROVED and CANCELLED.
        if (permissions.contains(PermissionCode.TASK_MANAGE.code())) {
            return true;
        }
        if (permissions.contains(PermissionCode.TASK_EXECUTE.code())
                && ((from == TO_DO && to == IN_PROGRESS)
                        || (from == IN_PROGRESS && to == IN_REVIEW)
                        || (from == REOPENED && (to == IN_PROGRESS || to == IN_REVIEW)))) {
            return true;
        }
        if (permissions.contains(PermissionCode.TASK_REVIEW.code())
                && from == IN_REVIEW && (to == APPROVED || to == REOPENED)) {
            return true;
        }
        return permissions.contains(PermissionCode.TASK_CANCEL.code())
                && to == CANCELLED && from != APPROVED && from != CANCELLED;
    }
}
