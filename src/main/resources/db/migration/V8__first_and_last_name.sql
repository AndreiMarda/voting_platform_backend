-- V8__first_and_last_name.sql
-- Users get a first and a last name. The username stays what people sign in with; the app shows
-- "first name last name" everywhere else.
-- Existing accounts were named after the person ("Ana Maria Ionescu"), so split that: the last word is the
-- last name, everything before it the first name(s). A one-word username leaves both empty and the app then
-- falls back to showing the username.
ALTER TABLE users ADD COLUMN first_name VARCHAR(60);
ALTER TABLE users ADD COLUMN last_name  VARCHAR(60);

UPDATE users
SET first_name = regexp_replace(username, '^(.*)\s+(\S+)$', '\1'),
    last_name  = regexp_replace(username, '^(.*)\s+(\S+)$', '\2')
WHERE username ~ '^.+\s+\S+$';
