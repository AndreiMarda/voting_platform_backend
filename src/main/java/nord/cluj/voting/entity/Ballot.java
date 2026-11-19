package nord.cluj.voting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** The choice made in a poll. Deliberately has no user and no timestamp, to keep votes anonymous. */
@Entity
@Table(name = "ballots")
public class Ballot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "poll_id", nullable = false)
    private Long pollId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Choice choice;

    protected Ballot() {
    }

    public Ballot(Long pollId, Choice choice) {
        this.pollId = pollId;
        this.choice = choice;
    }

    public UUID getId() { return id; }
    public Long getPollId() { return pollId; }
    public Choice getChoice() { return choice; }
}
