package nord.cluj.voting.repository;

import nord.cluj.voting.entity.PollEligibleVoter;
import nord.cluj.voting.entity.PollVoterId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PollEligibleVoterRepository extends JpaRepository<PollEligibleVoter, PollVoterId> {

    @Query("select e.id.userId from PollEligibleVoter e where e.id.pollId = :pollId")
    List<Long> findUserIdsByPollId(@Param("pollId") Long pollId);
}
