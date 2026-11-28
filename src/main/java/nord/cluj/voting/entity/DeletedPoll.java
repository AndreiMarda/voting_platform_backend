package nord.cluj.voting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * What is left of a deleted poll, so the session report can still list it. No names and no individual
 * votes: only counts, and the totals if the poll was closed when it was deleted.
 */
@Entity
@Table(name = "deleted_polls")
public class DeletedPoll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "poll_id", nullable = false)
    private Long pollId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "is_anonymous", nullable = false)
    private boolean anonymous;

    /** The status the poll had when it was deleted. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PollStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt = Instant.now();

    @Column(name = "eligible_count", nullable = false)
    private int eligibleCount;

    @Column(name = "voted_count", nullable = false)
    private int votedCount;

    @Column(name = "for_count")
    private Long forCount;

    @Column(name = "against_count")
    private Long againstCount;

    @Column(name = "abstain_count")
    private Long abstainCount;

    protected DeletedPoll() {
    }

    public DeletedPoll(Poll poll, int eligibleCount, int votedCount, Long forCount, Long againstCount, Long abstainCount) {
        this.pollId = poll.getId();
        this.sessionId = poll.getSessionId();
        this.title = poll.getTitle();
        this.anonymous = poll.isAnonymous();
        this.status = poll.getStatus();
        this.createdAt = poll.getCreatedAt();
        this.closedAt = poll.getClosedAt();
        this.eligibleCount = eligibleCount;
        this.votedCount = votedCount;
        this.forCount = forCount;
        this.againstCount = againstCount;
        this.abstainCount = abstainCount;
    }

    public Long getPollId() { return pollId; }
    public Long getSessionId() { return sessionId; }
    public String getTitle() { return title; }
    public boolean isAnonymous() { return anonymous; }
    public PollStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public int getEligibleCount() { return eligibleCount; }
    public int getVotedCount() { return votedCount; }
    public Long getForCount() { return forCount; }
    public Long getAgainstCount() { return againstCount; }
    public Long getAbstainCount() { return abstainCount; }
}
