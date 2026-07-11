package com.easytask.backend.user;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/** All fields optional; only non-null values are applied. */
public record UpdateUserRequest(
        @Size(min = 2, max = 100) String fullName,
        UUID roleId,
        String role
) {
}
