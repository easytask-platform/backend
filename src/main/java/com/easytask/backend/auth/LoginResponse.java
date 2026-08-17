package com.easytask.backend.auth;

import com.easytask.backend.role.DataScope;

import java.util.Set;
import java.util.UUID;

public record LoginResponse(
        UserPayload user,
        String accessToken,
        String refreshToken
) {

    /**
     * {@code role} is the role's display name (system roles keep the legacy
     * enum strings); {@code roleId}, {@code scope} and {@code permissions}
     * let clients gate UI without hardcoding role names (D13).
     */
    public record UserPayload(
            UUID id,
            String fullName,
            String email,
            String role,
            UUID roleId,
            DataScope scope,
            Set<String> permissions,
            String organizationName,
            boolean mustChangePassword) {
    }
}
