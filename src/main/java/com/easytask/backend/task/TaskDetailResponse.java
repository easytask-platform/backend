package com.easytask.backend.task;

import com.easytask.backend.tag.TagSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TaskDetailResponse(
        UUID id,
        UUID projectId,
        String projectName,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        LocalDate startDate,
        LocalDate dueDate,
        BigDecimal estimatedHours,
        BigDecimal totalLoggedHours,
        boolean overdue,
        List<AssigneeDetail> assignees,
        List<TagSummary> tags,
        boolean blocked,
        String blockedReason,
        int checklistDone,
        int checklistTotal,
        boolean pinned,
        Instant createdAt,
        Instant updatedAt
) {

    public record AssigneeDetail(UUID id, String fullName, String email, String avatarUrl) {
    }
}
