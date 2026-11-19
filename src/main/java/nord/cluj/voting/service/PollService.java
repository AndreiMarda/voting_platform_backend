package nord.cluj.voting.service;

import nord.cluj.voting.dto.PollDtos.ParticipationView;
import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.dto.PollDtos.ResultsView;
import nord.cluj.voting.entity.Ballot;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.Poll;
import nord.cluj.voting.entity.PollEligibleVoter;
import nord.cluj.voting.entity.PollStatus;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.BallotRepository;
import nord.cluj.voting.repository.PollEligibleVoterRepository;
import nord.cluj.voting.repository.PollParticipationRepository;
import nord.cluj.voting.repository.PollRepository;
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
    private final Duration onlineWindow;

    public PollService(PollRepository polls,
                       PollEligibleVoterRepository eligibleVoters,
                       PollParticipationRepository participation,
                       BallotRepository ballots,
                       UserRepository users,
                       @Value("${voting.online-window:PT2M}") Duration onlineWindow) {
        this.polls = polls;
        this.eligibleVoters = eligibleVoters;
        this.participation = participation;
        this.ballots = ballots;
        this.users = users;
        this.onlineWindow = onlineWindow;
    }

    /**
     * Launches a poll. The eligible voters are frozen now: everyone currently online, plus the creator.
     */
    @Transactional
    public PollView create(Long creatorId, String title, String description) {
        Poll poll = new Poll();
        poll.setTitle(title.trim());
        poll.setDescription(description == null || description.isBlank() ? null : description.trim());
        poll.setAnonymous(false); // non-anonymous for now; the flag is kept for later
        poll.setCreatedBy(users.getReferenceById(creatorId));
        polls.save(poll);

        Set<Long> voterIds = new LinkedHashSet<>();
        voterIds.add(creatorId);
        users.findByEnabledTrueAndLastSeenAtAfter(Instant.now().minus(onlineWindow))
                .forEach(u -> voterIds.add(u.getId()));
        eligibleVoters.saveAll(voterIds.stream().map(id -> new PollEligibleVoter(poll.getId(), id)).toList());

        return view(poll, creatorId);
    }

    /** Casts the user's single vote. */
    @Transactional
    public void vote(Long pollId, Long userId, Choice choice) {
        Poll poll = polls.findByIdForShare(pollId).orElseThrow(PollService::notFound);
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
    }

    @Transactional
    public PollView close(Long pollId, Long requesterId) {
        Poll poll = polls.findByIdForUpdate(pollId).orElseThrow(PollService::notFound);
        if (poll.getStatus() == PollStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This poll is already closed");
        }
        poll.close();
        return view(poll, requesterId);
    }

    @Transactional(readOnly = true)
    public PollView get(Long pollId, Long requesterId) {
        return view(polls.findById(pollId).orElseThrow(PollService::notFound), requesterId);
    }

    @Transactional(readOnly = true)
    public List<PollView> list(Long requesterId) {
        return polls.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(p -> view(p, requesterId)).toList();
    }

    /** Names of those who voted and those who have not. Visible to every logged-in user. */
    @Transactional(readOnly = true)
    public ParticipationView participation(Long pollId) {
        if (!polls.existsById(pollId)) {
            throw notFound();
        }
        Set<Long> votedIds = Set.copyOf(participation.findUserIdsByPollId(pollId));
        List<Long> eligibleIds = eligibleVoters.findUserIdsByPollId(pollId);
        Map<Long, String> names = new java.util.HashMap<>();
        users.findAllById(eligibleIds).forEach(u -> names.put(u.getId(), u.getUsername()));

        List<String> voted = eligibleIds.stream().filter(votedIds::contains).map(names::get).sorted().toList();
        List<String> notVoted = eligibleIds.stream().filter(id -> !votedIds.contains(id)).map(names::get).sorted().toList();
        return new ParticipationView(voted, notVoted);
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
        return new PollView(poll.getId(), poll.getTitle(), poll.getDescription(), poll.isAnonymous(),
                poll.getStatus(), poll.getCreatedAt(), poll.getClosedAt(),
                eligibleIds.contains(requesterId), voted, mine,
                votedIds.size(), eligibleIds.size());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Poll not found");
    }
}
