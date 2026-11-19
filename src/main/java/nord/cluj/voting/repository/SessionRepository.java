package nord.cluj.voting.repository;

import jakarta.persistence.LockModeType;
import nord.cluj.voting.entity.SessionStatus;
import nord.cluj.voting.entity.VotingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SessionRepository extends JpaRepository<VotingSession, Long> {

    Optional<VotingSession> findByStatus(SessionStatus status);

    /** Shared lock: polls can be created together, but ending the session waits for them. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from VotingSession s where s.status = :status")
    Optional<VotingSession> findByStatusForShare(@Param("status") SessionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from VotingSession s where s.id = :id")
    Optional<VotingSession> findByIdForUpdate(@Param("id") Long id);
}
