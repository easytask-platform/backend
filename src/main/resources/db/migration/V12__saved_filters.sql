-- P4-8 (D42): server-side per-user saved task filters. The `filters` payload
-- is the client's opaque task-filter object serialized as JSON text (≤ 2000
-- chars, capped in the service). Max 20 per user, unique name per user.

CREATE TABLE saved_filters (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    name       VARCHAR(50) NOT NULL,
    filters    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_filters_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_saved_filters_user ON saved_filters (user_id);
