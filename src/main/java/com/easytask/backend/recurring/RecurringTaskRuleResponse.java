package com.easytask.backend.recurring;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RecurringTaskRuleResponse(
        UUID id,
        UUID projectId,
        String title,
        RecurrenceFrequency frequency,
        int interval,
        LocalDate recurrenceStartDate,
        LocalDate recurrenceEndDate,
        List<UUID> assigneeIds
) {

    public static RecurringTaskRuleResponse from(RecurringTaskRule rule, List<UUID> assigneeIds) {
        return new RecurringTaskRuleResponse(rule.getId(), rule.getProject().getId(), rule.getTitle(),
                rule.getFrequency(), rule.getRecurrenceInterval(), rule.getRecurrenceStartDate(),
                rule.getRecurrenceEndDate(), assigneeIds);
    }
}
