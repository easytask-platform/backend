package com.easytask.backend.auth;

import com.easytask.backend.role.DataScope;

import java.util.Set;
import java.util.UUID;

/**
 * Security principal parsed from a JWT access token. Everything downstream
 * (org isolation, permission checks, visibility scoping) is derived from
 * this, never from request bodies. {@code roleName} is display/filter
 * metadata; enforcement uses {@code permissions} and {@code scope} only.
 */
public record AuthenticatedUser(
        UUID id,
        UUID organizationId,
        String roleName,
        DataScope scope,
        Set<String> permissions) {

    public boolean can(String permissionCode) {
        return permissions.contains(permissionCode);
    }
}
