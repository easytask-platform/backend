-- P4-4 (D34/D35): one-level nested comment replies + explicit @mentions
-- (FR-38, AF-03/AF-04). Replies reference their top-level parent; deleting a
-- parent cascades to its replies. Mentions store nothing new (the comment text
-- is the record) — they only fan out COMMENT_MENTION notifications. Note:
-- notifications.type is a plain VARCHAR(40) with no CHECK constraint in V1, so
-- the new COMMENT_REPLIED / COMMENT_MENTION types need no constraint change.

ALTER TABLE task_comments
    ADD COLUMN parent_comment_id UUID REFERENCES task_comments (id) ON DELETE CASCADE;

CREATE INDEX idx_task_comments_parent ON task_comments (parent_comment_id);
