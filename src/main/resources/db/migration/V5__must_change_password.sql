-- P3-2 (D24): forced first-login password change for admin-set passwords.
ALTER TABLE app_users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
