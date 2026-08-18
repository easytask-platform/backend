package com.easytask.backend.task;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** All fields optional; only non-null values are applied. */
public record UpdateTaskRequest(
        @Size(min = 3, max = 150) String title,
        @Size(max = 5000) String description,
        TaskPriority priority,
        LocalDate startDate,
        LocalDate dueDate,
        @Positive BigDecimal estimatedHours,
        List<UUID> assigneeIds,
        List<UUID> tagIds
) {
}
