package nord.cluj.voting.repository;

import nord.cluj.voting.entity.Ballot;
import nord.cluj.voting.entity.Choice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BallotRepository extends JpaRepository<Ballot, UUID> {

    interface ChoiceCount {
        Choice getChoice();
        long getTotal();
    }

    @Modifying
    @Query("delete from Ballot b where b.pollId = :pollId")
    void deleteByPollId(@Param("pollId") Long pollId);

    @Query("select b.choice as choice, count(b) as total from Ballot b where b.pollId = :pollId group by b.choice")
    List<ChoiceCount> countByChoice(@Param("pollId") Long pollId);
}
