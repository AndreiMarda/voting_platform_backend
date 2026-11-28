package nord.cluj.voting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "polls")
public class Poll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /** The voting session this poll belongs to; fixed at creation. */
    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionId;

    @Column(name = "is_anonymous", nullable = false)
    private boolean anonymous = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PollStatus status = PollStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    /** SHA-256 fingerprint of the ballots, made when the poll closed (null while open). See PollIntegrityService. */
    @Column(length = 64)
    private String seal;

    @Column(name = "sealed_ballots")
    private Integer sealedBallots;

    /** Closes the poll; satisfies the DB check that CLOSED requires closed_at. */
    public void close() {
        this.status = PollStatus.CLOSED;
        this.closedAt = Instant.now();
    }

    public Long getId() { return id; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isAnonymous() { return anonymous; }
    public void setAnonymous(boolean anonymous) { this.anonymous = anonymous; }

    public String getSeal() { return seal; }
    public Integer getSealedBallots() { return sealedBallots; }
    public void setSeal(String seal, int sealedBallots) {
        this.seal = seal;
        this.sealedBallots = sealedBallots;
    }

    public PollStatus getStatus() { return status; }

    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getClosedAt() { return closedAt; }
}
