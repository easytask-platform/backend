package com.easytask.backend.task;

import com.easytask.backend.tag.TagSummary;

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
        List<AssigneeSummary> assignees,
        List<TagSummary> tags,
        boolean blocked,
        String blockedReason,
        int checklistDone,
        int checklistTotal,
        boolean pinned
) {

    public record AssigneeSummary(UUID id, String fullName, String avatarUrl) {
    }
}
