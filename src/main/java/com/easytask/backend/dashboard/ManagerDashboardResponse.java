package com.easytask.backend.dashboard;

import java.util.List;

public record ManagerDashboardResponse(
        long managedTaskCount,
        long tasksAwaitingReview,
        long overdueTaskCount,
        TasksByStatusResponse tasksByStatus,
        List<WorkloadItemResponse> memberWorkload,
        List<ProjectProgressItemResponse> projectProgress
) {
}
