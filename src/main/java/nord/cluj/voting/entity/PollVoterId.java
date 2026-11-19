package nord.cluj.voting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/** Composite key (poll_id, user_id), shared by eligible voters and participation. */
@Embeddable
public class PollVoterId implements Serializable {

    @Column(name = "poll_id", nullable = false)
    private Long pollId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    protected PollVoterId() {
    }

    public PollVoterId(Long pollId, Long userId) {
        this.pollId = pollId;
        this.userId = userId;
    }

    public Long getPollId() { return pollId; }
    public Long getUserId() { return userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PollVoterId that)) return false;
        return Objects.equals(pollId, that.pollId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pollId, userId);
    }
}
