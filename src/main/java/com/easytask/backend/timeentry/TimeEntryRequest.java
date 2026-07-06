package com.easytask.backend.timeentry;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Body for POST; PATCH uses {@link UpdateTimeEntryRequest}. Hours: > 0 and <= 24. */
public record TimeEntryRequest(
        @NotNull LocalDate workDate,
        @NotNull @Positive @DecimalMax("24") BigDecimal hoursSpent,
        @Size(max = 5000) String note
) {
}
