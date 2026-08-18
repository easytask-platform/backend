-- P4-3 (D32): project-scoped colored task tags (FR-27/FR-28). Tag CRUD needs
-- task:manage; attach/detach rides on task create/update via tagIds and writes
-- TAG_ADDED / TAG_REMOVED activity events (FR-40). Note: task_activity_logs
-- .event_type is a plain VARCHAR(40) with no CHECK constraint in V1, so the
-- new event types need no constraint change.

CREATE TABLE tags (
    id         UUID PRIMARY KEY,
    project_id UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name       VARCHAR(30) NOT NULL,
    color      CHAR(7)     NOT NULL CHECK (color ~ '^#[0-9a-fA-F]{6}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tags_project_name UNIQUE (project_id, name)
);

CREATE INDEX idx_tags_project ON tags (project_id);

CREATE TABLE task_tags (
    task_id UUID NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    tag_id  UUID NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, tag_id)
);

CREATE INDEX idx_task_tags_tag ON task_tags (tag_id);
