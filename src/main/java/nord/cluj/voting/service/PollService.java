package nord.cluj.voting.service;

import nord.cluj.voting.dto.PollDtos.ParticipationView;
import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.dto.PollDtos.ResultsView;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.entity.Ballot;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.Poll;
import nord.cluj.voting.entity.PollEligibleVoter;
import nord.cluj.voting.entity.PollStatus;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.BallotRepository;
import nord.cluj.voting.repository.PollEligibleVoterRepository;
import nord.cluj.voting.repository.PollParticipationRepository;
import nord.cluj.voting.repository.PollRepository;
import nord.cluj.voting.repository.SessionRepository;
import nord.cluj.voting.entity.SessionStatus;
import nord.cluj.voting.entity.VotingSession;
import nord.cluj.voting.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PollService {

    private final PollRepository polls;
    private final PollEligibleVoterRepository eligibleVoters;
    private final PollParticipationRepository participation;
    private final BallotRepository ballots;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final Duration onlineWindow;
    private final AuditService audit;
    private final EventBroadcaster events;

    public PollService(PollRepository polls,
                       PollEligibleVoterRepository eligibleVoters,
                       PollParticipationRepository participation,
                       BallotRepository ballots,
                       UserRepository users,
                       SessionRepository sessions,
                       AuditService audit,
                       EventBroadcaster events,
                       @Value("${voting.online-window:PT2M}") Duration onlineWindow) {
        this.events = events;
        this.audit = audit;
        this.sessions = sessions;
        this.polls = polls;
        this.eligibleVoters = eligibleVoters;
        this.participation = participation;
        this.ballots = ballots;
        this.users = users;
        this.onlineWindow = onlineWindow;
    }

    /**
     * Launches a poll inside the running session. The eligible voters are frozen now: every regular user
     * who is currently online (administrators are not voters).
     */
    @Transactional
    public PollView create(Long creatorId, String title, String description) {
        return create(creatorId, title, description, false);
    }

    /**
     * @param anonymous if true, nobody (admins included) can see who voted, only how many have; the voter's
     *                  choice is not stored next to their name either
     */
    @Transactional
    public PollView create(Long creatorId, String title, String description, boolean anonymous) {
        // shared lock: ending the session waits for polls being created right now
        VotingSession session = sessions.findByStatusForShare(SessionStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "No session is running. Start a session first."));
        Poll poll = new Poll();
        poll.setSessionId(session.getId());
        poll.setTitle(capitalizeFirst(title.trim()));
        poll.setDescription(description == null || description.isBlank() ? null : description.trim());
        poll.setAnonymous(anonymous);
        poll.setCreatedBy(users.getReferenceById(creatorId));
        polls.save(poll);

        // Administrators run the votes but never take part in them, so only online regular users are eligible.
        Set<Long> voterIds = new LinkedHashSet<>();
        users.findByEnabledTrueAndLastSeenAtAfter(Instant.now().minus(onlineWindow)).stream()
                .filter(u -> u.getRole() == Role.USER)
                .forEach(u -> voterIds.add(u.getId()));
        eligibleVoters.saveAll(voterIds.stream().map(id -> new PollEligibleVoter(poll.getId(), id)).toList());

        audit.record(AuditAction.POLL_CREATED, "POLL", poll.getId(),
                "Created " + (anonymous ? "anonymous " : "") + "vote \"" + poll.getTitle() + "\" in session "
                        + session.getId() + " (" + voterIds.size() + " eligible voters)");
        events.publish("polls");
        return view(poll, creatorId);
    }

    /**
     * Late joiners: a regular user who comes online while votes are open is added to them, so they can vote too.
     * Called on every heartbeat; does nothing for administrators or users who are already in all open votes.
     *
     * @return the number of open votes the user was just added to
     */
    @Transactional
    public int enrollInOpenPolls(Long userId) {
        User user = users.findById(userId).orElse(null);
        if (user == null || user.getRole() != Role.USER || !user.isEnabled()) {
            return 0;
        }
        int joined = eligibleVoters.enrollInOpenPolls(userId);
        if (joined > 0) {
            events.publish("polls");     // "19 of 20" just became "19 of 21" for everyone
        }
        return joined;
    }

    /** Casts the user's single vote. */
    @Transactional
    public void vote(Long pollId, Long userId, Choice choice) {
        Poll poll = polls.findByIdForShare(pollId).orElseThrow(PollService::notFound);
        User voter = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Unknown user"));
        if (voter.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrators cannot vote");
        }
        if (poll.getStatus() != PollStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This poll is closed");
        }
        if (!eligibleVoters.findUserIdsByPollId(pollId).contains(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not eligible to vote in this poll");
        }
        // The primary key (poll_id, user_id) is what really guarantees one vote, even under concurrency.
        // The choice is kept next to the voter only for non-anonymous polls, so they can see it again.
        String ownChoice = poll.isAnonymous() ? null : choice.name();
        if (participation.insertIfAbsent(pollId, userId, ownChoice) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already voted in this poll");
        }
        ballots.save(new Ballot(pollId, choice));
        events.publish("polls");         // only a topic: the event never says who voted or what
    }

    @Transactional
    public PollView close(Long pollId, Long requesterId) {
        Poll poll = polls.findByIdForUpdate(pollId).orElseThrow(PollService::notFound);
        if (poll.getStatus() == PollStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This poll is already closed");
        }
        poll.close();
        audit.record(AuditAction.POLL_CLOSED, "POLL", pollId, "Closed vote \"" + poll.getTitle() + "\"");
        events.publish("polls");
        return view(poll, requesterId);
    }

    /**
     * Permanently deletes a poll in any state (open, being voted on, or closed), with all its votes,
     * participation records and eligible-voter list. The poll row is locked first, so votes that are
     * being cast at this very moment finish before the delete (and are erased with it).
     */
    @Transactional
    public void delete(Long pollId) {
        Poll poll = polls.findByIdForUpdate(pollId).orElseThrow(PollService::notFound);
        // remember what is being erased: the audit entry is all that is left of this vote
        int voted = participation.findUserIdsByPollId(pollId).size();
        int eligible = eligibleVoters.findUserIdsByPollId(pollId).size();
        audit.record(AuditAction.POLL_DELETED, "POLL", pollId,
                "Deleted " + (poll.isAnonymous() ? "anonymous " : "") + poll.getStatus().name().toLowerCase()
                        + " vote \"" + poll.getTitle() + "\" (" + voted + " of " + eligible + " had voted)");
        ballots.deleteByPollId(pollId);          // children first: they reference the poll
        participation.deleteByPollId(pollId);
        eligibleVoters.deleteByPollId(pollId);   // participation references this one, so it goes after it
        polls.delete(poll);
        events.publish("polls");
    }

    @Transactional(readOnly = true)
    public PollView get(Long pollId, Long requesterId) {
        return view(polls.findById(pollId).orElseThrow(PollService::notFound), requesterId);
    }

    /** @param sessionId only that session's polls; null = all polls */
    @Transactional(readOnly = true)
    public List<PollView> list(Long requesterId, Long sessionId) {
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt");
        List<Poll> found = sessionId == null ? polls.findAll(newestFirst) : polls.findBySessionId(sessionId, newestFirst);
        return found.stream().map(p -> view(p, requesterId)).toList();
    }

    /**
     * Who voted and who has not, by name only. Visible to every logged-in user.
     * While the poll is open, "not voted" lists only the eligible users who are online right now (based on
     * their heartbeat); {@code notVotedOffline} counts the eligible users left out because they are offline.
     * Once the poll is closed, "not voted" lists every eligible user who did not vote.
     */
    @Transactional(readOnly = true)
    public ParticipationView participation(Long pollId) {
        Poll poll = polls.findById(pollId).orElseThrow(PollService::notFound);
        if (poll.isAnonymous()) {
            // no names for anyone: neither who voted nor who has not (that would reveal voters by elimination)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This vote is anonymous: only the number of voters is shown");
        }
        Set<Long> votedIds = Set.copyOf(participation.findUserIdsByPollId(pollId));
        List<Long> eligibleIds = eligibleVoters.findUserIdsByPollId(pollId);

        Map<Long, User> people = new java.util.HashMap<>();
        users.findAllById(eligibleIds).forEach(u -> people.put(u.getId(), u));
        Instant onlineSince = Instant.now().minus(onlineWindow);
        boolean open = poll.getStatus() == PollStatus.OPEN;

        List<String> voted = eligibleIds.stream().filter(votedIds::contains)
                .map(id -> people.get(id).getUsername()).sorted().toList();
        List<String> notVoted = new java.util.ArrayList<>();
        int offline = 0;
        for (Long id : eligibleIds) {
            User u = people.get(id);
            if (votedIds.contains(id) || u.getRole() != Role.USER) {
                continue;
            }
            boolean online = u.isEnabled() && u.getLastSeenAt() != null && u.getLastSeenAt().isAfter(onlineSince);
            if (!open || online) {
                notVoted.add(u.getUsername());
            } else {
                offline++;
            }
        }
        notVoted.sort(String.CASE_INSENSITIVE_ORDER);
        return new ParticipationView(voted, notVoted, offline);
    }

    /** Totals per choice. Only available once the poll is closed. */
    @Transactional(readOnly = true)
    public ResultsView results(Long pollId) {
        Poll poll = polls.findById(pollId).orElseThrow(PollService::notFound);
        if (poll.getStatus() != PollStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Results are available once the poll is closed");
        }
        Map<Choice, Long> tally = new EnumMap<>(Choice.class);
        for (Choice c : Choice.values()) {
            tally.put(c, 0L);
        }
        ballots.countByChoice(pollId).forEach(row -> tally.put(row.getChoice(), row.getTotal()));
        return new ResultsView(tally, tally.values().stream().mapToLong(Long::longValue).sum());
    }

    private PollView view(Poll poll, Long requesterId) {
        List<Long> eligibleIds = eligibleVoters.findUserIdsByPollId(poll.getId());
        List<Long> votedIds = participation.findUserIdsByPollId(poll.getId());
        boolean voted = votedIds.contains(requesterId);
        Choice mine = voted ? participation.findChoice(poll.getId(), requesterId).orElse(null) : null;
        return new PollView(poll.getId(), poll.getSessionId(), poll.getTitle(), poll.getDescription(), poll.isAnonymous(),
                poll.getStatus(), poll.getCreatedAt(), poll.getClosedAt(),
                eligibleIds.contains(requesterId), voted, mine,
                votedIds.size(), eligibleIds.size());
    }

    /** Makes the first character a capital letter; the rest of the title is left exactly as typed. */
    static String capitalizeFirst(String text) {
        if (text.isEmpty()) {
            return text;
        }
        int first = text.codePointAt(0);
        return Character.toString(Character.toTitleCase(first)) + text.substring(Character.charCount(first));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Poll not found");
    }
}
