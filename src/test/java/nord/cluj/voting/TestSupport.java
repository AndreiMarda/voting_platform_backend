package nord.cluj.voting;

import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.dto.SessionDtos.SessionView;
import nord.cluj.voting.service.PollService;
import nord.cluj.voting.service.SessionService;

import java.util.Optional;

/**
 * Polls need a running session. Tests run inside a transaction that is rolled back, so if the dev database
 * already has a real active session we may end it here: the change never reaches the database.
 * Only call these from @Transactional tests.
 */
public final class TestSupport {

    public static final String TEST_SESSION = "Test session";

    private TestSupport() {
    }

    /** Makes sure the running session is ours (ending any real one for the duration of the test). */
    public static SessionView ensureTestSession(SessionService sessions, Long adminId) {
        Optional<SessionView> current = sessions.current();
        if (current.isPresent() && TEST_SESSION.equals(current.get().name())) {
            return current.get();
        }
        current.ifPresent(real -> sessions.end(real.id()));
        return sessions.start(TEST_SESSION, adminId);
    }

    /** Ends whatever session is running (inside the test transaction only). */
    public static void endRunningSession(SessionService sessions) {
        sessions.current().ifPresent(s -> sessions.end(s.id()));
    }

    public static PollView poll(SessionService sessions, PollService polls, Long adminId, String title) {
        ensureTestSession(sessions, adminId);
        return polls.create(adminId, title, null);
    }
}
