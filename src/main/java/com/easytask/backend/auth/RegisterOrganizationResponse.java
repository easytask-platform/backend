package com.easytask.backend.auth;

import java.util.UUID;

public record RegisterOrganizationResponse(
        OrganizationPayload organization,
        UserPayload user,
        String accessToken,
        String refreshToken
) {

    public record OrganizationPayload(UUID id, String name) {
    }

    public record UserPayload(UUID id, String fullName, String email, String role) {
    }
}
