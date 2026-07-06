package com.easytask.backend.dashboard;

import java.math.BigDecimal;
import java.util.UUID;

public record ProjectProgressItemResponse(
        UUID projectId,
        String projectName,
        int progressPercent,
        long taskCount,
        long approvedTaskCount,
        long overdueTaskCount,
        BigDecimal estimatedHours,
        BigDecimal loggedHours
) {
}
