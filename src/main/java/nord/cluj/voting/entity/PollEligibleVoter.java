package nord.cluj.voting.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Frozen list of users allowed to vote in a poll (snapshot taken at creation). */
@Entity
@Table(name = "poll_eligible_voters")
public class PollEligibleVoter {

    @EmbeddedId
    private PollVoterId id;

    protected PollEligibleVoter() {
    }

    public PollEligibleVoter(Long pollId, Long userId) {
        this.id = new PollVoterId(pollId, userId);
    }

    public PollVoterId getId() { return id; }
}
