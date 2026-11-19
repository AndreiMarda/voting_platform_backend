package nord.cluj.voting.entity;

/** Things worth remembering. Casting a vote is intentionally not one of them (anonymity). */
public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    LOGIN_LOCKED,
    LOGOUT,
    USER_CREATED,
    USER_DELETED,
    PASSWORD_RESET,
    PASSWORD_CHANGED,
    SESSION_STARTED,
    SESSION_ENDED,
    POLL_CREATED,
    POLL_CLOSED,
    POLL_DELETED
}
