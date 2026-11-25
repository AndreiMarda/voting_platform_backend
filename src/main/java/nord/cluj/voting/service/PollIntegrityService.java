package nord.cluj.voting.service;

import nord.cluj.voting.entity.Ballot;
import nord.cluj.voting.entity.Poll;
import nord.cluj.voting.entity.PollStatus;
import nord.cluj.voting.repository.BallotRepository;
import nord.cluj.voting.repository.PollParticipationRepository;
import nord.cluj.voting.repository.PollRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Tamper evidence for the votes. When a poll closes, a SHA-256 "seal" of all its ballots is stored. Anyone can
 * recompute it later: if a ballot was changed, added or removed (for example directly in the database), the
 * seal no longer matches.
 *
 * The seal covers the poll id, and for every ballot its random id and its choice, sorted by that random id. It
 * deliberately has no user and no casting order in it, so it cannot link a ballot to a voter.
 * Keep a copy of the seal outside the database (it is shown on the closed poll and in the report): someone
 * with full database access could otherwise rewrite the ballots and the stored seal together.
 */
@Service
public class PollIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(PollIntegrityService.class);

    public enum Status {
        /** Still open: sealed when it closes. */
        OPEN,
        /** Closed and the ballots still match the seal. */
        SEALED,
        /** Closed before seals existed and not sealed yet. */
        NOT_SEALED,
        /** Something does not add up. */
        BROKEN
    }

    public record IntegrityView(Long pollId, Status status, int ballots, int voters, String seal, String message) {
    }

    private final PollRepository polls;
    private final BallotRepository ballots;
    private final PollParticipationRepository participation;

    public PollIntegrityService(PollRepository polls, BallotRepository ballots,
                                PollParticipationRepository participation) {
        this.polls = polls;
        this.ballots = ballots;
        this.participation = participation;
    }

    /** Seals a poll that has just been closed. The caller holds the poll's row lock, so no vote can slip in. */
    @Transactional
    public void seal(Poll poll) {
        List<Ballot> all = ballots.findByPollId(poll.getId());
        poll.setSeal(fingerprint(poll.getId(), all), all.size());
    }

    /** Seals polls that were closed before seals existed (runs at startup; does nothing once they all have one). */
    @Transactional
    public int sealLegacyPolls() {
        int sealed = 0;
        for (Poll poll : polls.findAll()) {
            if (poll.getStatus() == PollStatus.CLOSED && poll.getSeal() == null) {
                seal(poll);
                sealed++;
            }
        }
        if (sealed > 0) {
            log.info("Sealed {} poll(s) that were closed before integrity seals existed", sealed);
        }
        return sealed;
    }

    @Transactional(readOnly = true)
    public IntegrityView verify(Long pollId) {
        Poll poll = polls.findById(pollId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Poll not found"));
        List<Ballot> all = ballots.findByPollId(pollId);
        int voters = participation.findUserIdsByPollId(pollId).size();

        if (all.size() != voters) {
            return new IntegrityView(pollId, Status.BROKEN, all.size(), voters, poll.getSeal(),
                    all.size() + " ballots are stored but " + voters + " people are recorded as having voted.");
        }
        if (poll.getStatus() == PollStatus.OPEN) {
            return new IntegrityView(pollId, Status.OPEN, all.size(), voters, null,
                    "This vote is still open. It is sealed when it closes; so far the number of ballots matches the number of voters.");
        }
        if (poll.getSeal() == null) {
            return new IntegrityView(pollId, Status.NOT_SEALED, all.size(), voters, null,
                    "This vote was closed before integrity seals existed.");
        }
        if (!poll.getSeal().equals(fingerprint(pollId, all))) {
            return new IntegrityView(pollId, Status.BROKEN, all.size(), voters, poll.getSeal(),
                    "The ballots no longer match the seal made when this vote closed: they were changed afterwards.");
        }
        return new IntegrityView(pollId, Status.SEALED, all.size(), voters, poll.getSeal(),
                "The ballots are exactly as they were when this vote closed.");
    }

    static String fingerprint(Long pollId, List<Ballot> all) {
        StringBuilder text = new StringBuilder("poll:").append(pollId);
        all.stream().sorted(Comparator.comparing(b -> b.getId().toString()))
                .forEach(b -> text.append('\n').append(b.getId()).append(':').append(b.getChoice().name()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Java runtime has SHA-256
        }
    }
}
