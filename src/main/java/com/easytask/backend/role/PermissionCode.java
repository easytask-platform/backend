package com.easytask.backend.role;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fixed permission catalog (decision D8). Codes are `module:action` and
 * are bound to code paths — roles compose them, nobody invents new ones at
 * runtime. Mirrored by the `permissions` table (V3 migration).
 */
public enum PermissionCode {

    USER_READ("user:read", "List and view organization users (scoped)"),
    USER_MANAGE("user:manage", "Create, update, deactivate users and reset their passwords"),
    TEAM_READ("team:read", "List teams and team members"),
    TEAM_MANAGE("team:manage", "Create and update teams and their membership"),
    ROLE_MANAGE("role:manage", "Create, update, delete roles and view the permission catalog"),
    PROJECT_MANAGE("project:manage", "Create and update projects and their members"),
    TASK_MANAGE("task:manage", "Create and update tasks"),
    TASK_EXECUTE("task:execute", "Work assigned tasks: start and submit for review"),
    TASK_REVIEW("task:review", "Approve or reopen tasks in review"),
    TASK_CANCEL("task:cancel", "Cancel non-approved tasks"),
    RECURRING_MANAGE("recurring:manage", "Create and view recurring task rules"),
    DASHBOARD_MANAGER("dashboard:manager", "View the manager dashboard and reports"),
    DASHBOARD_ADMIN("dashboard:admin", "View the organization-wide admin dashboard");

    private final String code;
    private final String description;

    PermissionCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static Optional<PermissionCode> fromCode(String code) {
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst();
    }
}
