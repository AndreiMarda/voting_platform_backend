# API contract

Everything is JSON over HTTP on the same origin as the pages. Auth is a **session cookie**
(`JSESSIONID`, HttpOnly, SameSite=Strict); there are no tokens and no CSRF tokens.
**Keep the frontend and API on one origin** (use a dev-server proxy for `/api`, `/login`, `/logout`),
otherwise the cookie will not be sent.

## Errors
- `401` – not signed in (or the account was deleted/disabled). Go to the login page.
- `403` with `{"error":"PASSWORD_CHANGE_REQUIRED"}` – the user must change their password first;
  only `/api/auth/me`, `/api/auth/change-password` and `/logout` work until they do.
- `403` – not allowed (e.g. a normal user calling an admin endpoint).
- Any other 4xx – `{"detail": "<human readable reason>"}`, suitable for a toast.
  Validation failures are `400`; "already voted", "poll closed", "username taken", … are `409`.

## Session
| Call | Who | Notes |
|---|---|---|
| `POST /login` (form: `username`, `password`) | anyone | Success → redirect to `/`; failure → redirect to `/login.html?error` |
| `POST /logout` | signed in | Redirects to `/login.html?logout` |
| `GET /api/auth/me` | signed in | `{id, username, role: "ADMIN"\|"USER", mustChangePassword}` |
| `POST /api/auth/change-password` | signed in | `{currentPassword, newPassword}` (8–72 chars, must differ from current and from the default password) → `204` |
| `POST /api/heartbeat` | signed in | → `204`. Call every ~30 s; "online" users (seen in the last 2 min) become the voters of a newly launched poll |

## Polls
`PollView = {id, title, description, anonymous, status: "OPEN"|"CLOSED", createdAt, closedAt,
eligible, hasVoted, myChoice: "FOR"|"AGAINST"|"ABSTAIN"|null, votedCount, eligibleCount}`

`eligible`, `hasVoted` and `myChoice` are about the **requesting user**. `myChoice` is only ever
returned to the voter themselves.

| Call | Who | Notes |
|---|---|---|
| `GET /api/polls` | signed in | All polls, newest first |
| `GET /api/polls/{id}` | signed in | One `PollView` |
| `POST /api/polls` | admin | `{title (≤500, required), description (≤5000)}` → `201` + `PollView` |
| `POST /api/polls/{id}/votes` | signed in | `{choice}` → `204`. `403` not eligible, `409` already voted / closed. One vote per user, final |
| `POST /api/polls/{id}/close` | admin | → `PollView`. `409` if already closed |
| `GET /api/polls/{id}/participation` | signed in | `{voted: [usernames], notVoted: [usernames]}` – who, never what |
| `GET /api/polls/{id}/results` | signed in | `{tally: {FOR, AGAINST, ABSTAIN}, total}`. `409` until the poll is closed |

## Users (admin only)
`UserView = {id, username, role, enabled, mustChangePassword, createdAt}` (never a password hash)

| Call | Notes |
|---|---|
| `GET /api/users` | List |
| `POST /api/users` | `{username (3–30 letters only), password (8–72), role}` → `201`. `409` if the name is taken (case-insensitive). The user must change the password at first login |
| `POST /api/users/{id}/reset-password` | Sets the default password (`voting.default-password`) and forces a change → `204` |
| `DELETE /api/users/{id}` | → `204`. `409` if it is yourself or the user is part of any poll |
