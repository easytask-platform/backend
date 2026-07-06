package com.easytask.backend.timeentry;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TimeEntryResponse(
        UUID id,
        UUID taskId,
        Employee employee,
        LocalDate workDate,
        BigDecimal hoursSpent,
        String note,
        Instant createdAt
) {

    public record Employee(UUID id, String fullName) {
    }

    public static TimeEntryResponse from(TimeEntry entry) {
        var assignee = entry.getTaskAssignment().getAssignee();
        return new TimeEntryResponse(entry.getId(), entry.getTaskAssignment().getTask().getId(),
                new Employee(assignee.getId(), assignee.getFullName()),
                entry.getWorkDate(), entry.getHoursSpent(), entry.getNote(), entry.getCreatedAt());
    }
}
