package com.easytask.backend.task;

import com.easytask.backend.user.UserRole;

import static com.easytask.backend.task.TaskStatus.APPROVED;
import static com.easytask.backend.task.TaskStatus.CANCELLED;
import static com.easytask.backend.task.TaskStatus.IN_PROGRESS;
import static com.easytask.backend.task.TaskStatus.IN_REVIEW;
import static com.easytask.backend.task.TaskStatus.REOPENED;
import static com.easytask.backend.task.TaskStatus.TO_DO;

/**
 * Contract transition matrix:
 * Employee: TO_DO→IN_PROGRESS, IN_PROGRESS→IN_REVIEW, REOPENED→IN_PROGRESS|IN_REVIEW.
 * Manager/Admin: IN_REVIEW→APPROVED|REOPENED, any non-approved (and non-cancelled) task →CANCELLED.
 */
public final class TaskStatusTransitions {

    private TaskStatusTransitions() {
    }

    public static boolean isAllowed(UserRole actorRole, TaskStatus from, TaskStatus to) {
        if (actorRole == UserRole.EMPLOYEE) {
            return (from == TO_DO && to == IN_PROGRESS)
                    || (from == IN_PROGRESS && to == IN_REVIEW)
                    || (from == REOPENED && (to == IN_PROGRESS || to == IN_REVIEW));
        }
        if (from == IN_REVIEW && (to == APPROVED || to == REOPENED)) {
            return true;
        }
        return to == CANCELLED && from != APPROVED && from != CANCELLED;
    }
}
