package com.easytask.backend.auth;

import com.easytask.backend.user.UserRole;

import java.util.UUID;

/**
 * Security principal parsed from a JWT access token. Everything downstream
 * (org isolation, role checks) is derived from this, never from request bodies.
 */
public record AuthenticatedUser(UUID id, UUID organizationId, UserRole role) {
}
