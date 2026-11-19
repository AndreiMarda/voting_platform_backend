-- V1__init_schema.sql
-- Place in: src/main/resources/db/migration/
-- Core schema for the voting platform (PostgreSQL 13+).

-- ============================================================
-- USERS (accounts are created by an admin; no public signup)
-- ============================================================
CREATE TABLE users (
                       id                   BIGSERIAL    PRIMARY KEY,
                       username             VARCHAR(30)  NOT NULL,
                       password_hash        VARCHAR(255) NOT NULL,   -- BCrypt hash (60 chars), never the plain password
                       role                 VARCHAR(10)  NOT NULL CHECK (role IN ('ADMIN', 'USER')),
                       enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
                       must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
                       last_seen_at         TIMESTAMPTZ,             -- updated by the frontend heartbeat
                       created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- usernames are unique regardless of upper/lower case
CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));
CREATE INDEX idx_users_last_seen ON users (last_seen_at);

-- ============================================================
-- POLLS (created only by admins; no end date, closed manually)
-- ============================================================
CREATE TABLE polls (
                       id           BIGSERIAL    PRIMARY KEY,
                       title        TEXT         NOT NULL CHECK (length(trim(title)) > 0),
                       description  TEXT,
                       is_anonymous BOOLEAN      NOT NULL DEFAULT FALSE,
                       status       VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
                       created_by   BIGINT       NOT NULL REFERENCES users (id),
                       created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       closed_at    TIMESTAMPTZ,
                       CONSTRAINT chk_polls_closed CHECK (
                           (status = 'OPEN'   AND closed_at IS NULL) OR
                           (status = 'CLOSED' AND closed_at IS NOT NULL)
                           )
);

CREATE INDEX idx_polls_status ON polls (status);

-- ============================================================
-- POLL_ELIGIBLE_VOTERS (frozen list of who should vote,
-- snapshotted from the online users when the poll is created)
-- ============================================================
CREATE TABLE poll_eligible_voters (
                                      poll_id BIGINT NOT NULL REFERENCES polls (id),
                                      user_id BIGINT NOT NULL REFERENCES users (id),
                                      PRIMARY KEY (poll_id, user_id)
);

-- ============================================================
-- POLL_PARTICIPATION (who voted, never what)
-- Primary key = one vote per user per poll.
-- Composite FK = only users from the snapshot can vote.
-- ============================================================
CREATE TABLE poll_participation (
                                    poll_id BIGINT NOT NULL,
                                    user_id BIGINT NOT NULL,
                                    PRIMARY KEY (poll_id, user_id),
                                    FOREIGN KEY (poll_id, user_id)
                                        REFERENCES poll_eligible_voters (poll_id, user_id)
);

-- ============================================================
-- BALLOTS (what was chosen, never by whom)
-- No user_id and no timestamp on purpose.
-- ============================================================
CREATE TABLE ballots (
                         id      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                         poll_id BIGINT      NOT NULL REFERENCES polls (id),
                         choice  VARCHAR(10) NOT NULL CHECK (choice IN ('FOR', 'AGAINST', 'ABSTAIN'))
);

CREATE INDEX idx_ballots_poll ON ballots (poll_id);