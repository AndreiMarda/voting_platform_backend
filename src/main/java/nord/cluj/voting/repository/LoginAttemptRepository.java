package nord.cluj.voting.repository;

import nord.cluj.voting.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    List<LoginAttempt> findByUsernameKeyAndAttemptedAtAfterOrderByAttemptedAtAsc(String usernameKey, Instant since);

    List<LoginAttempt> findByIpAndAttemptedAtAfterOrderByAttemptedAtAsc(String ip, Instant since);

    @Modifying
    @Transactional
    @Query("delete from LoginAttempt a where a.usernameKey = :key")
    void clearFor(@Param("key") String usernameKey);

    @Modifying
    @Transactional
    @Query("delete from LoginAttempt a where a.attemptedAt < :cutoff")
    void deleteOlderThan(@Param("cutoff") Instant cutoff);
}
