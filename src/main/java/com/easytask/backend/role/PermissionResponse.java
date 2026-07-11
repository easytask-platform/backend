package com.easytask.backend.role;

public record PermissionResponse(String code, String description) {

    public static PermissionResponse from(PermissionCode permission) {
        return new PermissionResponse(permission.code(), permission.description());
    }
}
