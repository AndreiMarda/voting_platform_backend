package nord.cluj.voting.service;

import nord.cluj.voting.dto.SessionDtos.SessionView;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.entity.Poll;
import nord.cluj.voting.entity.PollStatus;
import nord.cluj.voting.entity.SessionStatus;
import nord.cluj.voting.entity.VotingSession;
import nord.cluj.voting.repository.PollRepository;
import nord.cluj.voting.repository.SessionRepository;
import nord.cluj.voting.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class SessionService {

    private final SessionRepository sessions;
    private final PollRepository polls;
    private final UserRepository users;

    private final AuditService audit;
    private final EventBroadcaster events;

    public SessionService(SessionRepository sessions, PollRepository polls, UserRepository users, AuditService audit,
                          EventBroadcaster events) {
        this.events = events;
        this.audit = audit;
        this.sessions = sessions;
        this.polls = polls;
        this.users = users;
    }

    /** Starts a session. Only one can be active at a time (also enforced by a unique index). */
    @Transactional
    public SessionView start(String rawName, Long adminId) {
        String name = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A session needs a name");
        }
        sessions.findByStatus(SessionStatus.ACTIVE).ifPresent(active -> {
            throw alreadyRunning(active.getName());
        });
        VotingSession session = new VotingSession();
        session.setName(name);
        session.setStartedBy(users.getReferenceById(adminId));
        try {
            sessions.saveAndFlush(session);
        } catch (DataIntegrityViolationException e) { // two admins started one at the same instant
            throw alreadyRunning(null);
        }
        audit.record(AuditAction.SESSION_STARTED, "SESSION", session.getId(), "Started session \"" + name + "\"");
        events.publish("session");
        return view(session);
    }

    /** Ends a session and closes every vote that is still open in it. */
    @Transactional
    public SessionView end(Long id) {
        VotingSession session = sessions.findByIdForUpdate(id).orElseThrow(SessionService::notFound);
        if (session.getStatus() == SessionStatus.ENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This session has already ended");
        }
        int closed = 0;
        for (Poll poll : polls.findBySessionIdAndStatus(id, PollStatus.OPEN)) {
            poll.close();
            closed++;
        }
        session.end();
        sessions.flush();   // make the ENDED update land before any new session is inserted
        audit.record(AuditAction.SESSION_ENDED, "SESSION", id,
                "Ended session \"" + session.getName() + "\" (" + closed + " open vote" + (closed == 1 ? "" : "s") + " closed)");
        events.publish("session");
        events.publish("polls");         // its open votes were closed
        return view(session);
    }

    /** The running session, if any. */
    @Transactional(readOnly = true)
    public Optional<SessionView> current() {
        return sessions.findByStatus(SessionStatus.ACTIVE).map(this::view);
    }

    @Transactional(readOnly = true)
    public SessionView get(Long id) {
        return view(sessions.findById(id).orElseThrow(SessionService::notFound));
    }

    /** All sessions, newest first. */
    @Transactional(readOnly = true)
    public List<SessionView> list() {
        return sessions.findAll(Sort.by(Sort.Direction.DESC, "startedAt", "id")).stream().map(this::view).toList();
    }

    private SessionView view(VotingSession s) {
        return new SessionView(s.getId(), s.getName(), s.getStatus(), s.getStartedBy().getUsername(),
                s.getStartedAt(), s.getEndedAt(), polls.countBySessionId(s.getId()));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
    }

    private static ResponseStatusException alreadyRunning(String name) {
        return new ResponseStatusException(HttpStatus.CONFLICT, name == null
                ? "A session is already running. End it before starting a new one."
                : "The session \"" + name + "\" is already running. End it before starting a new one.");
    }
}
