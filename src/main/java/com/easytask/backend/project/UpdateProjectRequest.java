package com.easytask.backend.project;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** All fields optional; only non-null values are applied. */
public record UpdateProjectRequest(
        @Size(min = 2, max = 100) String name,
        @Size(max = 5000) String description,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate dueDate
) {
}
