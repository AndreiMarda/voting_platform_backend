-- V11__poll_seal_varchar.sql
-- The seal is a text column as far as the application is concerned (a 64 character hex string).
ALTER TABLE polls ALTER COLUMN seal TYPE VARCHAR(64);
