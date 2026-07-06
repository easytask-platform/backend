package com.easytask.backend.project;

import java.time.LocalDate;
import java.util.UUID;

public record ProjectDetailResponse(
        UUID id,
        String name,
        String description,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate dueDate,
        int progressPercent,
        long memberCount,
        TaskSummary taskSummary
) {

    public record TaskSummary(long toDo, long inProgress, long inReview, long approved, long reopened,
                              long cancelled) {
    }
}
