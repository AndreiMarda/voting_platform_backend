-- V2__widen_password_hash.sql
-- A BCrypt hash is 60 characters. Some databases were baselined with the column at VARCHAR(30)
-- (Flyway skipped V1 there), which cannot hold a hash. Widen it to match V1. No-op on fresh databases.
ALTER TABLE users ALTER COLUMN password_hash TYPE VARCHAR(255);
