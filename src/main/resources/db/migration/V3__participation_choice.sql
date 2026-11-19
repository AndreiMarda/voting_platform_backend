-- V3__participation_choice.sql
-- Lets a voter see their own choice again after voting.
-- Only filled for NON-anonymous polls (polls.is_anonymous = false); for anonymous polls it stays NULL,
-- so the "who voted" and "what was voted" records remain unlinked there.
-- The API only ever returns this value to the voter themselves.
ALTER TABLE poll_participation
    ADD COLUMN choice VARCHAR(10) CHECK (choice IN ('FOR', 'AGAINST', 'ABSTAIN'));
