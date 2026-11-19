-- Runs once, when the Docker volume is first created (see docker-compose.yml).
-- A separate database for the automated tests, so running them never touches your real data.
-- Flyway creates the tables in it on the first test run.
CREATE DATABASE nord_cluj_voting_platform_test;
