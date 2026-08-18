-- P4-1 (D30): profile pictures; stored file name (UUID + extension) under
-- easytask.storage.dir/avatars, NULL = no avatar (clients fall back to initials).
ALTER TABLE app_users
    ADD COLUMN avatar_filename VARCHAR(64);
