-- P4-6 (D37): blocked/waiting flag with reason (AF-05/AF-06). A flag, not a
-- status — the task status machine is untouched. Reason is required while
-- blocked and cleared on unblock. Note: task_activity_logs.event_type and
-- notifications.type are plain VARCHAR(40) columns with no CHECK constraint
-- in V1, so the new TASK_BLOCKED / TASK_UNBLOCKED types need no constraint
-- change.

ALTER TABLE tasks
    ADD COLUMN blocked        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN blocked_reason VARCHAR(300);
