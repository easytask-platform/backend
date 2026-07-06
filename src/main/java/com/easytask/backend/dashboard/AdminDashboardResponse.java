package com.easytask.backend.dashboard;

import com.easytask.backend.task.TaskStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AdminDashboardResponse(
        long totalUsers,
        long activeUsers,
        long teamCount,
        long projectCount,
        long taskCount,
        TasksByStatusResponse tasksByStatus,
        List<OverdueTaskItem> overdueTasks
) {

    public record OverdueTaskItem(UUID id, String title, String projectName, LocalDate dueDate,
                                  TaskStatus status) {
    }
}
