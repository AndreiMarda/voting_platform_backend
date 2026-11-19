-- V5__voting_sessions.sql
-- Polls are created inside a voting session. An admin starts a session (with a mandatory name) and ends it
-- later; only one session can be active at a time. Everything stays in the database as history.

CREATE TABLE voting_sessions (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0),
    status     VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ENDED')),
    started_by BIGINT       NOT NULL REFERENCES users (id),
    started_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at   TIMESTAMPTZ,
    CONSTRAINT chk_voting_sessions_ended CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL) OR
        (status = 'ENDED'  AND ended_at IS NOT NULL)
    )
);

-- at most one ACTIVE session, enforced by the database itself
CREATE UNIQUE INDEX uq_one_active_session ON voting_sessions (status) WHERE status = 'ACTIVE';

ALTER TABLE polls ADD COLUMN session_id BIGINT REFERENCES voting_sessions (id);

-- Polls that already exist were created before sessions did: put them in one ended "legacy" session.
INSERT INTO voting_sessions (name, status, started_by, started_at, ended_at)
SELECT 'Before sessions', 'ENDED', (SELECT created_by FROM polls ORDER BY id LIMIT 1), min(created_at), now()
FROM polls
HAVING count(*) > 0;

UPDATE polls SET session_id = (SELECT id FROM voting_sessions WHERE name = 'Before sessions') WHERE session_id IS NULL;

-- a poll cannot stay open inside an ended session
UPDATE polls SET status = 'CLOSED', closed_at = now() WHERE status = 'OPEN';

ALTER TABLE polls ALTER COLUMN session_id SET NOT NULL;
CREATE INDEX idx_polls_session ON polls (session_id);
