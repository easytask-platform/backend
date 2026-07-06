package com.easytask.backend.recurring;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringTaskGenerationServiceTest {

    private static final LocalDate BASE = LocalDate.of(2026, 1, 31);

    @Test
    void advancesByFrequencyTimesInterval() {
        assertThat(RecurringTaskGenerationService.nextRunDate(BASE, RecurrenceFrequency.DAILY, 1))
                .isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(RecurringTaskGenerationService.nextRunDate(BASE, RecurrenceFrequency.DAILY, 3))
                .isEqualTo(LocalDate.of(2026, 2, 3));
        assertThat(RecurringTaskGenerationService.nextRunDate(BASE, RecurrenceFrequency.WEEKLY, 2))
                .isEqualTo(LocalDate.of(2026, 2, 14));
        // month-end clamping
        assertThat(RecurringTaskGenerationService.nextRunDate(BASE, RecurrenceFrequency.MONTHLY, 1))
                .isEqualTo(LocalDate.of(2026, 2, 28));
    }
}
