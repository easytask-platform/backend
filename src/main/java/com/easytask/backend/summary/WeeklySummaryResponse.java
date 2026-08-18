package com.easytask.backend.summary;

import com.easytask.backend.task.TaskListItemResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * Personal weekly summary (P4-9, D38), computed on the fly for the caller.
 * Each list uses the standard task-list-item shape.
 */
public record WeeklySummaryResponse(
        LocalDate weekStart,
        LocalDate weekEnd,
        List<TaskListItemResponse> completed,
        List<TaskListItemResponse> overdue,
        List<TaskListItemResponse> upcoming
) {
}
