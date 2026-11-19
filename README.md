# Voting platform (backend + prototype frontend)

Spring Boot 4 / Java 21 / PostgreSQL. Admins start a named **session**, create votes inside it (normal or
anonymous) and users vote once. The prototype frontend is plain HTML/JS in `src/main/resources/static`.
The API is described in [API.md](API.md).

## Setup

1. Copy `.env.example` to `.env` and fill it in (`DB_PASSWORD`, `DEFAULT_PASSWORD`, optionally `ADMIN_USERNAME` and
   `ADMIN_PASSWORD` to create the first admin). `.env` is git-ignored: never commit it.
2. Have a PostgreSQL database, either way:
   - **Installed on your machine**: create a database called `nord_cluj_voting_platform` (port 5432).
   - **With Docker**: `docker compose up -d`. It listens on **5433**, so add `DB_PORT=5433` to `.env`.
     The first start also creates a separate database for the tests.
3. `.\gradlew.bat bootRun`, then open http://localhost:8080. The tables are created automatically (Flyway,
   `src/main/resources/db/migration`).

## Tests

`.\gradlew.bat test`

By default the tests use the same database as the app (inside transactions that are rolled back). To use a
separate one, set `TEST_DB_NAME=nord_cluj_voting_platform_test` in `.env` (the Docker database creates it for
you; with a local PostgreSQL run `CREATE DATABASE nord_cluj_voting_platform_test;` once).

## Production

Run behind an HTTPS reverse proxy (for example Caddy) with `SPRING_PROFILES_ACTIVE=prod`, which makes the login
cookie HTTPS-only and trusts the proxy's forwarded headers. Only the proxy must be able to reach the app's port.
For live updates through nginx, turn proxy buffering off for `/api/events`.
