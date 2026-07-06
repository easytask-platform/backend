package com.easytask.backend.team;

import jakarta.validation.constraints.Size;

/** Both fields optional; only non-null values are applied. */
public record UpdateTeamRequest(
        @Size(min = 2, max = 100) String name,
        @Size(max = 5000) String description
) {
}
