package com.easytask.backend.recurring;

import java.time.LocalDate;

/** One upcoming run-date of a recurring rule, flagged when an exception skips it. */
public record OccurrenceResponse(LocalDate date, boolean skipped) {
}
