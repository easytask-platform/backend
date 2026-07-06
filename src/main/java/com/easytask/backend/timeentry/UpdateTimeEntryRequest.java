package com.easytask.backend.timeentry;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** All fields optional; only non-null values are applied. */
public record UpdateTimeEntryRequest(
        LocalDate workDate,
        @Positive @DecimalMax("24") BigDecimal hoursSpent,
        @Size(max = 5000) String note
) {
}
