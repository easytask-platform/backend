package com.easytask.backend.auth;

import com.easytask.backend.role.DataScope;

import java.util.Set;
import java.util.UUID;

public record MeResponse(
        UUID id,
        String fullName,
        String email,
        String role,
        UUID roleId,
        DataScope scope,
        Set<String> permissions,
        String organizationName
) {
}
