package nord.cluj.voting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.PollStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PollDtos {

    private PollDtos() {
    }

    /** {@code anonymous} is optional (default false): an anonymous vote only ever shows how many have voted. */
    public record CreatePollRequest(@NotBlank @Size(max = 500) String title,
                                    @Size(max = 5000) String description,
                                    Boolean anonymous) {
    }

    public record VoteRequest(@NotNull Choice choice) {
    }

    /**
     * What the current user sees about a poll. {@code myChoice} is the requester's own choice only;
     * it is null if they have not voted (or the poll is anonymous).
     */
    public record PollView(Long id, Long sessionId, String title, String description, boolean anonymous,
                           PollStatus status, Instant createdAt, Instant closedAt,
                           boolean eligible, boolean hasVoted, Choice myChoice,
                           int votedCount, int eligibleCount) {
    }

    /**
     * Who voted and who has not yet. Never says what anyone chose. While a poll is open, {@code notVoted}
     * only includes users who are online now; {@code notVotedOffline} counts the offline ones left out.
     */
    public record ParticipationView(List<String> voted, List<String> notVoted, int notVotedOffline) {
    }

    public record ResultsView(Map<Choice, Long> tally, long total) {
    }
}
