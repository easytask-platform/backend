package com.easytask.backend.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Role can be given as {@code roleId} (preferred) or the legacy {@code role}
 * name (system role names are the old enum strings, so pre-RBAC clients keep
 * working). Exactly one must be present.
 */
public record CreateUserRequest(
        @NotBlank @Size(min = 2, max = 100) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72) String initialPassword,
        UUID roleId,
        String role
) {
}
