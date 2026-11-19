package nord.cluj.voting.repository;

import nord.cluj.voting.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByRole(nord.cluj.voting.entity.Role role);

    /** Enabled users whose heartbeat is newer than {@code since}, i.e. currently "online". */
    List<User> findByEnabledTrueAndLastSeenAtAfter(Instant since);

    @Modifying
    @Query("update User u set u.lastSeenAt = :now where u.id = :id")
    void touch(@Param("id") Long id, @Param("now") Instant now);
}
