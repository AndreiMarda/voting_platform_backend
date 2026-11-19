package nord.cluj.voting.repository;

import nord.cluj.voting.entity.PollEligibleVoter;
import nord.cluj.voting.entity.PollVoterId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PollEligibleVoterRepository extends JpaRepository<PollEligibleVoter, PollVoterId> {

    @Query("select e.id.userId from PollEligibleVoter e where e.id.pollId = :pollId")
    List<Long> findUserIdsByPollId(@Param("pollId") Long pollId);

    /**
     * Adds the user to every OPEN poll they are not in yet ("late joiners"). Returns how many polls they joined.
     * The insert is idempotent: polls they are already in are skipped.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO poll_eligible_voters (poll_id, user_id) "
            + "SELECT p.id, :userId FROM polls p WHERE p.status = 'OPEN' ON CONFLICT DO NOTHING", nativeQuery = true)
    int enrollInOpenPolls(@Param("userId") Long userId);

    @Modifying
    @Query("delete from PollEligibleVoter e where e.id.pollId = :pollId")
    void deleteByPollId(@Param("pollId") Long pollId);
}
