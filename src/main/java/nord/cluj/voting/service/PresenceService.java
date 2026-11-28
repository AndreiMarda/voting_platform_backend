package nord.cluj.voting.service;

import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Limits how many people can be online at the same time ({@code voting.max-online}, 60 by default).
 * "Online" is the same thing the Online users page shows: an enabled user whose last activity (login or heartbeat) is
 * within the online window. Administrators are always let in, so a full platform can never lock out the person running
 * the vote; they still count towards the total.
 *
 * Admitting someone (count, then mark them online) is done under a lock and committed before the lock is released, so
 * simultaneous logins cannot squeeze past the limit. That is correct for one running instance of the application.
 */
@Service
public class PresenceService {

    private final UserRepository users;
    private final TransactionTemplate tx;
    private final int maxOnline;
    private final Duration window;
    private final Object lock = new Object();

    public PresenceService(UserRepository users, PlatformTransactionManager txManager,
                           @Value("${voting.max-online:60}") int maxOnline,
                           @Value("${voting.online-window:PT2M}") Duration window) {
        this.users = users;
        this.tx = new TransactionTemplate(txManager);
        this.maxOnline = maxOnline;
        this.window = window;
    }

    public int maxOnline() {
        return maxOnline;
    }

    /** Whether this user's last activity is recent enough to count as online. */
    public boolean isOnline(User user) {
        return user.getLastSeenAt() != null && user.getLastSeenAt().isAfter(Instant.now().minus(window));
    }

    /**
     * Lets the user in and marks them online now, unless the platform is full.
     *
     * @return false if the platform is full (the user stays offline), or the user does not exist or is disabled
     */
    public boolean admit(Long userId) {
        synchronized (lock) {
            return Boolean.TRUE.equals(tx.execute(status -> {
                User user = users.findById(userId).orElse(null);
                if (user == null || !user.isEnabled()) {
                    return false;
                }
                Instant now = Instant.now();
                boolean alreadyOnline = isOnline(user);
                if (!alreadyOnline && user.getRole() != Role.ADMIN
                        && users.countByEnabledTrueAndLastSeenAtAfter(now.minus(window)) >= maxOnline) {
                    return false;
                }
                user.setLastSeenAt(now);
                users.saveAndFlush(user);
                return true;
            }));
        }
    }
}
