-- EasyTask initial schema
-- Naming per ERD_TaskManagementSystem.md; role/status values per Initial_API_Contract.md.

CREATE TABLE organizations (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE app_users (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id),
    full_name       VARCHAR(100) NOT NULL,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    role            VARCHAR(30)  NOT NULL
        CHECK (role IN ('ORGANIZATION_ADMIN', 'MANAGER', 'EMPLOYEE')),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_users_email UNIQUE (email)
);

CREATE INDEX idx_app_users_organization ON app_users (organization_id);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES app_users (id),
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE teams (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id),
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_teams_org_name UNIQUE (organization_id, name)
);

CREATE TABLE team_members (
    id          UUID PRIMARY KEY,
    team_id     UUID        NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES app_users (id),
    team_role   VARCHAR(20) NOT NULL DEFAULT 'MEMBER'
        CHECK (team_role IN ('MANAGER', 'MEMBER')),
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_members UNIQUE (team_id, user_id)
);

CREATE INDEX idx_team_members_user ON team_members (user_id);

CREATE TABLE projects (
    id                 UUID PRIMARY KEY,
    organization_id    UUID         NOT NULL REFERENCES organizations (id),
    created_by_user_id UUID         NOT NULL REFERENCES app_users (id),
    name               VARCHAR(100) NOT NULL,
    description        TEXT,
    status             VARCHAR(20)  NOT NULL DEFAULT 'PLANNED'
        CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    start_date         DATE,
    due_date           DATE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_projects_dates CHECK (
        start_date IS NULL OR due_date IS NULL OR due_date >= start_date)
);

CREATE INDEX idx_projects_organization ON projects (organization_id);

CREATE TABLE project_members (
    id           UUID PRIMARY KEY,
    project_id   UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES app_users (id),
    project_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER'
        CHECK (project_role IN ('MANAGER', 'MEMBER')),
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_members UNIQUE (project_id, user_id)
);

CREATE INDEX idx_project_members_user ON project_members (user_id);

CREATE TABLE recurring_task_rules (
    id                    UUID PRIMARY KEY,
    project_id            UUID         NOT NULL REFERENCES projects (id),
    created_by_user_id    UUID         NOT NULL REFERENCES app_users (id),
    title                 VARCHAR(150) NOT NULL,
    description           TEXT,
    priority              VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM'
        CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    estimated_hours       NUMERIC(6, 2) CHECK (estimated_hours IS NULL OR estimated_hours > 0),
    template_start_date   DATE,
    template_due_date     DATE,
    frequency             VARCHAR(10)  NOT NULL
        CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    recurrence_interval   INT          NOT NULL DEFAULT 1 CHECK (recurrence_interval > 0),
    recurrence_start_date DATE         NOT NULL,
    recurrence_end_date   DATE,
    next_run_at           TIMESTAMPTZ,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_recurring_rules_project ON recurring_task_rules (project_id);
CREATE INDEX idx_recurring_rules_next_run ON recurring_task_rules (next_run_at) WHERE is_active;

CREATE TABLE recurring_task_rule_assignees (
    id      UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES recurring_task_rules (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_users (id),
    CONSTRAINT uq_rule_assignees UNIQUE (rule_id, user_id)
);

CREATE TABLE tasks (
    id                     UUID PRIMARY KEY,
    project_id             UUID         NOT NULL REFERENCES projects (id),
    created_by_user_id     UUID         NOT NULL REFERENCES app_users (id),
    approved_by_user_id    UUID REFERENCES app_users (id),
    recurring_task_rule_id UUID REFERENCES recurring_task_rules (id),
    title                  VARCHAR(150) NOT NULL,
    description            TEXT,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'TO_DO'
        CHECK (status IN ('TO_DO', 'IN_PROGRESS', 'IN_REVIEW', 'APPROVED', 'REOPENED', 'CANCELLED')),
    priority               VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM'
        CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    start_date             DATE,
    due_date               DATE,
    estimated_hours        NUMERIC(6, 2) CHECK (estimated_hours IS NULL OR estimated_hours > 0),
    approved_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_tasks_dates CHECK (
        start_date IS NULL OR due_date IS NULL OR due_date >= start_date)
);

CREATE INDEX idx_tasks_project_status ON tasks (project_id, status);
CREATE INDEX idx_tasks_rule ON tasks (recurring_task_rule_id);
CREATE INDEX idx_tasks_due_date ON tasks (due_date);

CREATE TABLE task_assignments (
    id                  UUID PRIMARY KEY,
    task_id             UUID        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    assignee_user_id    UUID        NOT NULL REFERENCES app_users (id),
    assigned_by_user_id UUID        NOT NULL REFERENCES app_users (id),
    assigned_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_task_assignments UNIQUE (task_id, assignee_user_id)
);

CREATE INDEX idx_task_assignments_assignee ON task_assignments (assignee_user_id);

CREATE TABLE time_entries (
    id                 UUID PRIMARY KEY,
    task_assignment_id UUID          NOT NULL REFERENCES task_assignments (id) ON DELETE CASCADE,
    work_date          DATE          NOT NULL,
    hours_spent        NUMERIC(4, 2) NOT NULL CHECK (hours_spent > 0 AND hours_spent <= 24),
    note               TEXT,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_time_entries_assignment ON time_entries (task_assignment_id);

CREATE TABLE task_comments (
    id             UUID PRIMARY KEY,
    task_id        UUID        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    author_user_id UUID        NOT NULL REFERENCES app_users (id),
    content        TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_comments_task ON task_comments (task_id);

CREATE TABLE task_attachments (
    id                UUID PRIMARY KEY,
    task_id           UUID         NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    uploader_user_id  UUID         NOT NULL REFERENCES app_users (id),
    original_filename VARCHAR(255) NOT NULL,
    stored_filename   VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    file_size_bytes   BIGINT       NOT NULL CHECK (file_size_bytes > 0),
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_task_attachments_stored UNIQUE (stored_filename)
);

CREATE INDEX idx_task_attachments_task ON task_attachments (task_id);

CREATE TABLE task_activity_logs (
    id            UUID PRIMARY KEY,
    task_id       UUID        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    actor_user_id UUID        NOT NULL REFERENCES app_users (id),
    event_type    VARCHAR(40) NOT NULL,
    old_value     TEXT,
    new_value     TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_activity_task ON task_activity_logs (task_id, created_at);

CREATE TABLE notifications (
    id                UUID PRIMARY KEY,
    recipient_user_id UUID         NOT NULL REFERENCES app_users (id),
    task_id           UUID REFERENCES tasks (id) ON DELETE SET NULL,
    type              VARCHAR(40)  NOT NULL,
    title             VARCHAR(150) NOT NULL,
    message           TEXT         NOT NULL,
    is_read           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at           TIMESTAMPTZ
);

CREATE INDEX idx_notifications_recipient ON notifications (recipient_user_id, is_read, created_at);
