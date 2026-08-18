package com.easytask.backend.task;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code reason} is required (non-blank) when {@code blocked} is true; ignored/cleared otherwise. */
public record UpdateTaskBlockedRequest(
        @NotNull Boolean blocked,
        @Size(max = 300) String reason
) {
}
