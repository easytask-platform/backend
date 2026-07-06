package com.easytask.backend.dashboard;

import java.math.BigDecimal;
import java.util.UUID;

public record WorkloadItemResponse(
        UUID userId,
        String fullName,
        long assignedTaskCount,
        long inProgressTaskCount,
        long overdueTaskCount,
        BigDecimal loggedHours
) {
}
