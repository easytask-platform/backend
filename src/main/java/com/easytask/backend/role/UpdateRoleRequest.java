package com.easytask.backend.role;

import jakarta.validation.constraints.Size;

import java.util.Set;

/** All fields optional; only non-null values are applied. */
public record UpdateRoleRequest(
        @Size(min = 2, max = 50) String name,
        DataScope dataScope,
        Set<String> permissions
) {
}
