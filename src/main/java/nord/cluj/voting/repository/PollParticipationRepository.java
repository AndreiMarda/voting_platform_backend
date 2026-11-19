package nord.cluj.voting.repository;

import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.PollParticipation;
import nord.cluj.voting.entity.PollVoterId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PollParticipationRepository extends JpaRepository<PollParticipation, PollVoterId> {

    /**
     * Atomically records that the user voted. Returns 1 if recorded, 0 if the user had already voted.
     * (A plain save() would not work: with an assigned key JPA treats a duplicate as an update.)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO poll_participation (poll_id, user_id, choice) "
            + "VALUES (:pollId, :userId, CAST(:choice AS VARCHAR)) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("pollId") Long pollId, @Param("userId") Long userId,
                       @Param("choice") String choice);

    /** The voter's own stored choice; empty if they have not voted or the poll is anonymous. */
    @Query("select p.choice from PollParticipation p where p.id.pollId = :pollId and p.id.userId = :userId")
    Optional<Choice> findChoice(@Param("pollId") Long pollId, @Param("userId") Long userId);

    @Query("select p.id.userId from PollParticipation p where p.id.pollId = :pollId")
    List<Long> findUserIdsByPollId(@Param("pollId") Long pollId);
}
