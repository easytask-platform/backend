package com.easytask.backend.user;

import jakarta.validation.constraints.Size;

/** Both fields optional; only non-null values are applied. */
public record UpdateUserRequest(
        @Size(min = 2, max = 100) String fullName,
        UserRole role
) {
}
