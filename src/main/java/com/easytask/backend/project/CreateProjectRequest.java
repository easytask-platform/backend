package com.easytask.backend.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateProjectRequest(
        @NotBlank @Size(min = 2, max = 100) String name,
        @Size(max = 5000) String description,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate dueDate
) {
}
