package com.easytask.backend.auth;

import com.easytask.backend.user.UserRole;

import java.util.UUID;

public record LoginResponse(
        UserPayload user,
        String accessToken,
        String refreshToken
) {

    public record UserPayload(UUID id, String fullName, String email, UserRole role, String organizationName) {
    }
}
