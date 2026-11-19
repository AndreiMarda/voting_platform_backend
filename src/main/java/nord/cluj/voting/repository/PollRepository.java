package nord.cluj.voting.repository;

import jakarta.persistence.LockModeType;
import nord.cluj.voting.entity.Poll;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PollRepository extends JpaRepository<Poll, Long> {

    /** Shared row lock: many votes may proceed together, but closing the poll has to wait for them. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Poll p where p.id = :id")
    Optional<Poll> findByIdForShare(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Poll p where p.id = :id")
    Optional<Poll> findByIdForUpdate(@Param("id") Long id);
}
