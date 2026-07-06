package com.easytask.backend.task;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTaskStatusRequest(
        @NotNull TaskStatus status,
        @Size(max = 5000) String note
) {
}
