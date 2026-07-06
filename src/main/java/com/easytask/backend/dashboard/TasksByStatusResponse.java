package com.easytask.backend.dashboard;

import com.easytask.backend.task.TaskStatus;

import java.util.Map;

public record TasksByStatusResponse(
        long toDo,
        long inProgress,
        long inReview,
        long approved,
        long reopened,
        long cancelled
) {

    public static TasksByStatusResponse from(Map<TaskStatus, Long> counts) {
        return new TasksByStatusResponse(
                counts.getOrDefault(TaskStatus.TO_DO, 0L),
                counts.getOrDefault(TaskStatus.IN_PROGRESS, 0L),
                counts.getOrDefault(TaskStatus.IN_REVIEW, 0L),
                counts.getOrDefault(TaskStatus.APPROVED, 0L),
                counts.getOrDefault(TaskStatus.REOPENED, 0L),
                counts.getOrDefault(TaskStatus.CANCELLED, 0L));
    }

    public long total() {
        return toDo + inProgress + inReview + approved + reopened + cancelled;
    }
}
