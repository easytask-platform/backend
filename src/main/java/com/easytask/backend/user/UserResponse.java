package com.easytask.backend.user;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String fullName,
        String email,
        String role,
        UUID roleId,
        boolean active,
        Instant createdAt
) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(),
                user.getRole().getName(), user.getRole().getId(),
                user.isActive(), user.getCreatedAt());
    }
}
