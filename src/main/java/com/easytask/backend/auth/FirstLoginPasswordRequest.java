package com.easytask.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for POST /auth/first-login-password: an authenticated user who is still
 * on a temporary password (mustChangePassword) sets their own. No current
 * password is required — signing in with the temporary one already proved it.
 */
public record FirstLoginPasswordRequest(
        @NotBlank @Size(min = 8, max = 72) String newPassword
) {
}
