-- V10__poll_seal.sql
-- Integrity seal of a poll: when a poll closes, a SHA-256 fingerprint of all its ballots is stored here. Anyone can later
-- recompute it ("Verify integrity"); if a ballot was changed, added or removed in the database, it no longer matches.
-- Polls that were already closed get sealed the first time the application starts after this migration.
ALTER TABLE polls ADD COLUMN seal           CHAR(64);
ALTER TABLE polls ADD COLUMN sealed_ballots INTEGER;
