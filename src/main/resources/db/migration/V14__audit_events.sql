-- P4-11 (D39): persisted security audit events. Written IN ADDITION TO the
-- SLF4J SecurityAuditLog for the AF-08 sensitive admin actions + auth-critical
-- events (ROLE_CHANGED, USER_DEACTIVATED, ADMIN_PASSWORD_RESET,
-- PASSWORD_RESET_COMPLETED, REFRESH_TOKEN_REUSE). High-volume noise
-- (login success/failure, rate limits) stays log-only.

CREATE TABLE security_audit_events (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id),
    actor_id        UUID        REFERENCES app_users (id),
    target_user_id  UUID        REFERENCES app_users (id),
    event_type      VARCHAR(50) NOT NULL,
    detail          VARCHAR(300),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_security_audit_org_created ON security_audit_events (organization_id, created_at);

-- ---------------------------------------------------------------------------
-- New permission `audit:read` (mirror of PermissionCode.java — keep in sync),
-- granted to the ORGANIZATION_ADMIN system role of every existing org. New
-- orgs get it through RoleProvisioningService (all-permissions admin role).
-- ---------------------------------------------------------------------------

INSERT INTO permissions (code, description) VALUES
    ('audit:read', 'View the organization security audit log');

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, 'audit:read'
FROM roles r
WHERE r.is_system AND r.name = 'ORGANIZATION_ADMIN';
