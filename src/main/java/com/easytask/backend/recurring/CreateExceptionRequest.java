package com.easytask.backend.recurring;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateExceptionRequest(@NotNull LocalDate date) {
}
