-- P4-5 (D36): per-task checklists with progress on task cards (AF-01/AF-02).
-- Items are ordered by position (append = max(position)+1); done may be
-- toggled by assignees, structure needs task:manage. Checklist operations
-- deliberately write NO activity log entries and NO notifications.

CREATE TABLE task_checklist_items (
    id         UUID PRIMARY KEY,
    task_id    UUID         NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    title      VARCHAR(150) NOT NULL,
    done       BOOLEAN      NOT NULL DEFAULT FALSE,
    position   INT          NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_checklist_items_task ON task_checklist_items (task_id);
