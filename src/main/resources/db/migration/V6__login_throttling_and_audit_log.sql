-- V6__login_throttling_and_audit_log.sql

-- ============================================================
-- LOGIN_ATTEMPTS: recent FAILED logins, used to block brute-force guessing.
-- Keyed by the typed username (whether or not it exists, so attackers cannot tell) and by client IP.
-- A successful login clears the username's failures; old rows are purged by the application.
-- ============================================================
CREATE TABLE login_attempts (
    id           BIGSERIAL    PRIMARY KEY,
    username_key VARCHAR(100) NOT NULL,   -- typed username, normalised to lower case
    ip           VARCHAR(45)  NOT NULL,
    attempted_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_attempts_user ON login_attempts (username_key, attempted_at);
CREATE INDEX idx_login_attempts_ip   ON login_attempts (ip, attempted_at);

-- ============================================================
-- AUDIT_LOG: who did what, and when. Append-only: the application never updates or deletes rows.
-- No foreign keys on purpose: the actor's name is copied in, so the history survives user deletion.
-- Votes are deliberately NOT logged (it would link voters to anonymous votes).
-- ============================================================
CREATE TABLE audit_log (
    id          BIGSERIAL    PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_id    BIGINT,                  -- NULL when nobody was logged in (e.g. a failed login)
    actor_name  VARCHAR(100),
    action      VARCHAR(30)  NOT NULL,
    target_type VARCHAR(30),
    target_id   BIGINT,
    details     TEXT,
    ip          VARCHAR(45)
);
CREATE INDEX idx_audit_log_occurred ON audit_log (occurred_at);
