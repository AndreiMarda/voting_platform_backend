-- V12__deleted_polls.sql
-- Deleting a poll erases its ballots and voter lists, but a short record of it is kept here so the session's PDF
-- report can still list it ("DELETED"). It holds no names and no individual votes: only counts, and the totals
-- if the poll was already closed (an open poll's totals were never public, so they stay empty).
CREATE TABLE deleted_polls (
    id             BIGSERIAL PRIMARY KEY,
    poll_id        BIGINT       NOT NULL,
    session_id     BIGINT       NOT NULL REFERENCES voting_sessions (id),
    title          TEXT         NOT NULL,
    is_anonymous   BOOLEAN      NOT NULL,
    status         VARCHAR(10)  NOT NULL,      -- OPEN or CLOSED, as it was when deleted
    created_at     TIMESTAMPTZ  NOT NULL,
    closed_at      TIMESTAMPTZ,
    deleted_at     TIMESTAMPTZ  NOT NULL,
    eligible_count INTEGER      NOT NULL,
    voted_count    INTEGER      NOT NULL,
    for_count      BIGINT,
    against_count  BIGINT,
    abstain_count  BIGINT
);
CREATE INDEX idx_deleted_polls_session ON deleted_polls (session_id);
