package com.easytask.backend.task;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TaskListItemResponse(
        UUID id,
        UUID projectId,
        String projectName,
        String title,
        TaskStatus status,
        TaskPriority priority,
        LocalDate startDate,
        LocalDate dueDate,
        BigDecimal estimatedHours,
        BigDecimal totalLoggedHours,
        boolean overdue,
        List<AssigneeSummary> assignees
) {

    public record AssigneeSummary(UUID id, String fullName) {
    }
}
