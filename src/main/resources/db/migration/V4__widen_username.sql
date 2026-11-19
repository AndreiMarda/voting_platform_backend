-- V4__widen_username.sql
-- Usernames are now people's names (one or two first names + a last name), which can exceed 30 characters.
ALTER TABLE users ALTER COLUMN username TYPE VARCHAR(60);
