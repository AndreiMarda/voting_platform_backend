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
| `POST /login` (form: `username`, `password`) | anyone | The username is trimmed and matched case-insensitively with single spaces, so `"  ion   popescu "` finds `Ion Popescu`. Success → redirect to `/`; failure → redirect to `/login.html?error` |
| `POST /logout` | signed in | Redirects to `/login.html?logout` |
| `GET /api/auth/me` | signed in | `{id, username, role: "ADMIN"\|"USER", mustChangePassword}` |
| `POST /api/auth/change-password` | signed in | `{currentPassword, newPassword}` (8–72 chars, must differ from current and from the default password) → `204` |
| `POST /api/heartbeat` | signed in | → `204`. Call every ~30 s; "online" users (seen in the last 2 min) become the voters of a newly launched poll |

### Live updates (Server-Sent Events)
`GET /api/events` (any signed-in user, `text/event-stream`) is a connection that stays open. The server sends
`event: changed` with `data: polls` or `data: session` whenever votes or the session change (a vote was created,
closed, deleted or cast, someone joined an open vote, a session started or ended), plus `event: hello` on connect and a
keep-alive comment every 25 s. The events carry **only a topic, never data**: on receiving one, re-fetch what you show.
Events are sent after the change is committed. Browsers reconnect automatically (`EventSource`); keep a slow timer as a
safety net. Behind nginx turn proxy buffering off for this path.

### Login protection
Wrong passwords are counted per typed username and per client IP. 5 failures for one username, or 30 from one IP,
within 15 minutes block further logins (the right password included) until the oldest failure leaves that window.
A blocked `POST /login` redirects to `/login.html?locked=<minutes>` (otherwise `?error`). Blocked attempts are not
counted, and names that do not exist are blocked exactly like real ones. A correct login clears the username's count.
Limits: `voting.login.*` in `application.yaml`.

## Audit log (admin only)
`AuditView = {id, occurredAt, actorName, action, targetType, targetId, details, ip}`. `actorName` is the logged-in
user, or the typed name for failed logins. Actions: `LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGIN_LOCKED`, `LOGOUT`,
`USER_CREATED`, `USER_DELETED`, `PASSWORD_RESET`, `PASSWORD_CHANGED`, `SESSION_STARTED`, `SESSION_ENDED`,
`POLL_CREATED`, `POLL_CLOSED`, `POLL_DELETED`. **Casting a vote is never logged** (it would reveal voters of anonymous
votes). Read-only: there is no endpoint to edit or delete entries.

| Call | Notes |
|---|---|
| `GET /api/audit?limit=50&beforeId=` | Newest first (`limit` ≤ 200). Pass the last `id` you received as `beforeId` for the next, older page |

## Polls
`PollView = {id, sessionId, title, description, anonymous, status: "OPEN"|"CLOSED", createdAt, closedAt,
eligible, hasVoted, myChoice: "FOR"|"AGAINST"|"ABSTAIN"|null, votedCount, eligibleCount}`

`eligible`, `hasVoted` and `myChoice` are about the **requesting user**. `myChoice` is only ever
returned to the voter themselves.

| Call | Who | Notes |
|---|---|---|
| `GET /api/polls[?sessionId=]` | signed in | Newest first. With `sessionId` only that session's polls, otherwise all |
| `GET /api/polls/{id}` | signed in | One `PollView` |
| `POST /api/polls` | admin | `{title (≤500, required), description (≤5000), anonymous (optional, default false)}` → `201` + `PollView`. The title's first letter is capitalised. With `anonymous: true` nobody can see who voted (see `participation`) and the voter's choice is not stored next to their name; only counts and, after closing, totals are available. Created inside the running session; `409` "No session is running" if there is none. The eligible voters are the regular users online at this moment (admins are never voters). **Late joiners:** a regular user who comes online while the vote is open is added to it automatically (on their next heartbeat), so `eligibleCount` can grow |
| `POST /api/polls/{id}/votes` | signed in | `{choice}` → `204`. `403` for administrators ("Administrators cannot vote") or users who were not eligible, `409` already voted / closed. One vote per user, final |
| `POST /api/polls/{id}/close` | admin | → `PollView`. `409` if already closed |
| `DELETE /api/polls/{id}` | admin | → `204`. Works in any state (untouched, being voted on, or closed). Permanently erases the poll, its votes, participation and voter list. Voters who still have the page open get `404` |
| `GET /api/polls/{id}/participation` | signed in | `{voted: [usernames], notVoted: [usernames], notVotedOffline: n}` – who, never what. While the poll is open, `notVoted` only has eligible users who are online now (heartbeat) and `notVotedOffline` counts the eligible users left out; once closed, `notVoted` lists everyone who did not vote. Poll this every few seconds for a live view. **`403` for anonymous polls**, for everyone including admins and the voters themselves: use `votedCount` / `eligibleCount` from the `PollView` ("19 of 20 have voted") instead |
| `GET /api/polls/{id}/results` | signed in | `{tally: {FOR, AGAINST, ABSTAIN}, total}`. `409` until the poll is closed |

## Sessions
Votes live inside a **voting session** (not to be confused with the login session cookie). An admin starts a
session with a mandatory name; only one can be active at a time; polls can only be created in the active
session. Ending a session closes its open votes. Everything stays stored as history.

`SessionView = {id, name, status: "ACTIVE"|"ENDED", startedBy, startedAt, endedAt, pollCount}`

| Call | Who | Notes |
|---|---|---|
| `POST /api/sessions` | admin | `{name (required, ≤100)}` → `201` + `SessionView`. `400` if the name is blank, `409` if a session is already running |
| `GET /api/sessions/current` | signed in | The running session, or `204 No Content` if none |
| `GET /api/sessions` | signed in | All sessions, newest first |
| `GET /api/sessions/{id}` | signed in | One session (`404` if unknown) |
| `GET /api/sessions/{id}/export.csv` | signed in | CSV download (UTF-8 with BOM, one row per vote: type, status, dates, eligible, voted, For/Against/Abstain). Open votes have blank totals; no names ever. Formula-looking cells are prefixed with `'` |
| `POST /api/sessions/{id}/end` | admin | → `SessionView`. Closes the session's open votes. `409` if already ended |

## Users (admin only)
`UserView = {id, username, role, enabled, mustChangePassword, createdAt}` (never a password hash)

| Call | Notes |
|---|---|
| `GET /api/users` | List |
| `GET /api/users/online` | Users logged in right now (heartbeat within the last 2 min, enabled), A–Z: `[{id, username, role, lastSeenAt}]`. Logging out removes a user immediately |
| `POST /api/users` | `{username, password (8–72), role}` → `201`. The username is a person's name: one or two first names + a last name (2–3 words, letters only, ≤60 chars). It is normalised first (trimmed, single spaces, every word capitalised: `"  ion  POPESCU "` → `Ion Popescu`); `400` if it breaks the rule, `409` if taken (case-insensitive). The user must change the password at first login |
| `POST /api/users/{id}/reset-password` | Sets the default password (`voting.default-password`) and forces a change → `204` |
| `DELETE /api/users/{id}` | → `204`. `409` if it is yourself or the user is part of any poll |
