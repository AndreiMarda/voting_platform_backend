package nord.cluj.voting.repository;

import jakarta.persistence.LockModeType;
import nord.cluj.voting.entity.Poll;
import nord.cluj.voting.entity.PollStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PollRepository extends JpaRepository<Poll, Long> {

    List<Poll> findBySessionId(Long sessionId, Sort sort);

    List<Poll> findBySessionIdAndStatus(Long sessionId, PollStatus status);

    long countBySessionId(Long sessionId);

    /** Shared row lock: many votes may proceed together, but closing the poll has to wait for them. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Poll p where p.id = :id")
    Optional<Poll> findByIdForShare(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Poll p where p.id = :id")
    Optional<Poll> findByIdForUpdate(@Param("id") Long id);
}
