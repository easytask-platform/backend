-- P4-7 (D33): personal daily-focus pins (FR-29). Pins are private to the
-- pinning user (max 20 each), never touch the task lifecycle, and write no
-- activity or notifications.

CREATE TABLE task_pins (
    user_id   UUID        NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    task_id   UUID        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    pinned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, task_id)
);

CREATE INDEX idx_task_pins_task ON task_pins (task_id);
