package com.easytask.backend.auth;

import com.easytask.backend.user.UserRole;

import java.util.UUID;

public record MeResponse(
        UUID id,
        String fullName,
        String email,
        UserRole role,
        String organizationName
) {
}
