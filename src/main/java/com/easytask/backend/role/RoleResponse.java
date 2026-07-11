package com.easytask.backend.role;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public record RoleResponse(
        UUID id,
        String name,
        DataScope dataScope,
        boolean system,
        Set<String> permissions,
        long userCount
) {

    public static RoleResponse from(Role role, long userCount) {
        return new RoleResponse(role.getId(), role.getName(), role.getDataScope(),
                role.isSystem(), new TreeSet<>(role.getPermissions()), userCount);
    }
}
