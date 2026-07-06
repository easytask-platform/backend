package com.easytask.backend.project;

import java.time.LocalDate;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String description,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate dueDate,
        int progressPercent,
        long memberCount
) {

    public static ProjectResponse from(Project project, int progressPercent, long memberCount) {
        return new ProjectResponse(project.getId(), project.getName(), project.getDescription(),
                project.getStatus(), project.getStartDate(), project.getDueDate(), progressPercent, memberCount);
    }
}
