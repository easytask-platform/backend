-- Dynamic RBAC (Phase 2, decisions D8-D12):
--   permissions  = fixed catalog defined by backend code (module:action)
--   roles        = organization-scoped bundles of permissions + a data scope
--   system roles = the three legacy enum roles, provisioned per organization,
--                  locked against edit/delete. Names keep the legacy enum
--                  strings so API payloads stay backward compatible.

CREATE TABLE permissions (
    code        VARCHAR(50)  PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE roles (
    id              UUID        PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id),
    name            VARCHAR(50) NOT NULL,
    data_scope      VARCHAR(20) NOT NULL
        CHECK (data_scope IN ('ORGANIZATION', 'MANAGED', 'ASSIGNED')),
    is_system       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_roles_org_name UNIQUE (organization_id, name)
);

CREATE INDEX idx_roles_organization ON roles (organization_id);

CREATE TABLE role_permissions (
    role_id         UUID        NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_code VARCHAR(50) NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_id, permission_code)
);

-- ---------------------------------------------------------------------------
-- Permission catalog (mirror of PermissionCode.java — keep in sync)
-- ---------------------------------------------------------------------------

INSERT INTO permissions (code, description) VALUES
    ('user:read',         'List and view organization users (scoped)'),
    ('user:manage',       'Create, update, deactivate users and reset their passwords'),
    ('team:read',         'List teams and team members'),
    ('team:manage',       'Create and update teams and their membership'),
    ('role:manage',       'Create, update, delete roles and view the permission catalog'),
    ('project:manage',    'Create and update projects and their members'),
    ('task:manage',       'Create and update tasks'),
    ('task:execute',      'Work assigned tasks: start and submit for review'),
    ('task:review',       'Approve or reopen tasks in review'),
    ('task:cancel',       'Cancel non-approved tasks'),
    ('recurring:manage',  'Create and view recurring task rules'),
    ('dashboard:manager', 'View the manager dashboard and reports'),
    ('dashboard:admin',   'View the organization-wide admin dashboard');

-- ---------------------------------------------------------------------------
-- System roles for every existing organization
-- ---------------------------------------------------------------------------

INSERT INTO roles (id, organization_id, name, data_scope, is_system)
SELECT gen_random_uuid(), o.id, r.name, r.scope, TRUE
FROM organizations o
CROSS JOIN (VALUES
    ('ORGANIZATION_ADMIN', 'ORGANIZATION'),
    ('MANAGER',            'MANAGED'),
    ('EMPLOYEE',           'ASSIGNED')
) AS r (name, scope);

INSERT INTO role_permissions (role_id, permission_code)
SELECT r.id, p.code
FROM roles r
JOIN permissions p ON (
       (r.name = 'ORGANIZATION_ADMIN')
    OR (r.name = 'MANAGER' AND p.code IN (
            'user:read', 'team:read', 'project:manage', 'task:manage',
            'task:review', 'task:cancel', 'recurring:manage', 'dashboard:manager'))
    OR (r.name = 'EMPLOYEE' AND p.code = 'task:execute')
)
WHERE r.is_system;

-- ---------------------------------------------------------------------------
-- Move app_users from the enum column to a role FK
-- ---------------------------------------------------------------------------

ALTER TABLE app_users ADD COLUMN role_id UUID REFERENCES roles (id);

UPDATE app_users u
SET role_id = r.id
FROM roles r
WHERE r.organization_id = u.organization_id
  AND r.is_system
  AND r.name = u.role;

ALTER TABLE app_users ALTER COLUMN role_id SET NOT NULL;
ALTER TABLE app_users DROP COLUMN role;

CREATE INDEX idx_app_users_role ON app_users (role_id);
