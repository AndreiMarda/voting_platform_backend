-- V7__user_profile.sql
-- Email, ID ONCR and last login for users.
--  * email:          NULL for accounts that existed before this migration (shown as a dash). New accounts without an
--                    email are flagged must_add_email and have to add it at their next login.
--  * id_oncr:        two capital letters + digits, at most 7 characters, set by the user; unique. Without it a user
--                    can have an account but cannot vote.
--  * last_login_at:  set on every successful login (last_seen_at is the "online now" heartbeat, a different thing).
ALTER TABLE users ADD COLUMN email          VARCHAR(254);
ALTER TABLE users ADD COLUMN id_oncr        VARCHAR(7);
ALTER TABLE users ADD COLUMN last_login_at  TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN must_add_email BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX uq_users_id_oncr ON users (id_oncr) WHERE id_oncr IS NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_id_oncr_format CHECK (id_oncr IS NULL OR id_oncr ~ '^[A-Z]{2}[0-9]{1,5}$');
