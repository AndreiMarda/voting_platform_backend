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
    USER_ACTIVATED,
    USER_DEACTIVATED,
    PROFILE_UPDATED,
    USER_REGISTERED,
    REGISTRATION_OPENED,
    REGISTRATION_CLOSED,
    SESSION_STARTED,
    SESSION_ENDED,
    SESSION_RENAMED,
    POLL_CREATED,
    POLL_CLOSED,
    POLL_DELETED
}
