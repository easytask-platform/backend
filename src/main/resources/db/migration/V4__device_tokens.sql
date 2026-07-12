CREATE TABLE device_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES app_users (id),
    token        VARCHAR(512) NOT NULL,
    platform     VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_tokens_token UNIQUE (token),
    CONSTRAINT chk_device_tokens_platform CHECK (platform IN ('ANDROID', 'IOS', 'WEB'))
);

CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);
