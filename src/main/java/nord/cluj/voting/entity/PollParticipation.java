package nord.cluj.voting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Records that a user voted in a poll. The choice is stored only for non-anonymous polls, so the voter
 * can be shown their own choice; it is never exposed to anyone else.
 */
@Entity
@Table(name = "poll_participation")
public class PollParticipation {

    @EmbeddedId
    private PollVoterId id;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Choice choice;

    protected PollParticipation() {
    }

    public PollParticipation(Long pollId, Long userId) {
        this.id = new PollVoterId(pollId, userId);
    }

    public PollVoterId getId() { return id; }

    public Choice getChoice() { return choice; }
}
