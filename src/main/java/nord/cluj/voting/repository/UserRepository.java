package nord.cluj.voting.repository;

import nord.cluj.voting.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByRole(nord.cluj.voting.entity.Role role);

    Optional<User> findByIdOncr(String idOncr);

    /** Accounts using this email (any case). Emails are meant to be unique, so normally zero or one. */
    java.util.List<User> findByEmailIgnoreCase(String email);

    /** Ids of users whose username, first name, last name or full name contains the (already escaped, lower-case) pattern. */
    @Query("select u.id from User u where lower(u.username) like :pattern escape '!' "
            + "or lower(u.firstName) like :pattern escape '!' or lower(u.lastName) like :pattern escape '!' "
            + "or lower(concat(u.firstName, ' ', u.lastName)) like :pattern escape '!'")
    List<Long> findIdsByNameLike(@Param("pattern") String pattern);

    /** Called on every successful login. */
    @Modifying
    @Transactional
    @Query("update User u set u.lastLoginAt = :now where u.id = :id")
    void recordLogin(@Param("id") Long id, @Param("now") Instant now);

    /** Enabled users whose heartbeat is newer than {@code since}, i.e. currently "online". */
    List<User> findByEnabledTrueAndLastSeenAtAfter(Instant since);

    long countByEnabledTrueAndLastSeenAtAfter(Instant since);

    @Modifying
    @Query("update User u set u.lastSeenAt = :now where u.id = :id")
    void touch(@Param("id") Long id, @Param("now") Instant now);

    /** Called on logout, so the user stops showing as online immediately. */
    @Modifying
    @Transactional
    @Query("update User u set u.lastSeenAt = null where u.id = :id")
    void clearLastSeen(@Param("id") Long id);
}
