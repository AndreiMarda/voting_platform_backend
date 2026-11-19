package nord.cluj.voting.service;

import nord.cluj.voting.entity.LoginAttempt;
import nord.cluj.voting.repository.LoginAttemptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Brute-force protection. Failed logins are remembered per typed username and per client IP:
 * <ul>
 *   <li>{@code max-failures-per-user} failures for one username within the window -> that username is blocked
 *       (even for the right password) until its oldest failure leaves the window;</li>
 *   <li>{@code max-failures-per-ip} failures from one IP (any usernames) -> that IP is blocked the same way.</li>
 * </ul>
 * The typed name counts whether or not the account exists, so blocking reveals nothing about real accounts.
 * Blocked attempts are not recorded, so an attacker cannot keep a real user locked out by hammering.
 */
@Service
public class LoginAttemptService {

    private final LoginAttemptRepository attempts;
    private final int maxPerUser;
    private final int maxPerIp;
    private final Duration window;

    public LoginAttemptService(LoginAttemptRepository attempts,
                               @Value("${voting.login.max-failures-per-user:5}") int maxPerUser,
                               @Value("${voting.login.max-failures-per-ip:30}") int maxPerIp,
                               @Value("${voting.login.window:PT15M}") Duration window) {
        this.attempts = attempts;
        this.maxPerUser = maxPerUser;
        this.maxPerIp = maxPerIp;
        this.window = window;
    }

    /** How long this username / IP must wait before trying again; empty if they may try now. */
    @Transactional(readOnly = true)
    public Optional<Duration> blockedFor(String typedUsername, String ip) {
        Instant now = Instant.now();
        Instant since = now.minus(window);
        Duration wait = waitFor(attempts.findByUsernameKeyAndAttemptedAtAfterOrderByAttemptedAtAsc(key(typedUsername), since),
                maxPerUser, now);
        Duration byIp = waitFor(attempts.findByIpAndAttemptedAtAfterOrderByAttemptedAtAsc(clean(ip), since),
                maxPerIp, now);
        if (byIp.compareTo(wait) > 0) {
            wait = byIp;
        }
        return wait.isZero() ? Optional.empty() : Optional.of(wait);
    }

    @Transactional
    public void recordFailure(String typedUsername, String ip) {
        Instant now = Instant.now();
        attempts.save(new LoginAttempt(key(typedUsername), clean(ip), now));
        attempts.deleteOlderThan(now.minus(window.multipliedBy(2)));   // housekeeping: keep the table tiny
    }

    /** A correct login wipes that username's failures (the IP's count is left alone). */
    @Transactional
    public void recordSuccess(String typedUsername) {
        attempts.clearFor(key(typedUsername));
    }

    /** Failures are in ascending time order; once there are {@code max} of them, wait for the oldest to expire. */
    private Duration waitFor(List<LoginAttempt> failures, int max, Instant now) {
        if (failures.size() < max) {
            return Duration.ZERO;
        }
        Instant freeAt = failures.get(failures.size() - max).getAttemptedAt().plus(window);
        Duration wait = Duration.between(now, freeAt);
        return wait.isNegative() ? Duration.ZERO : wait;
    }

    static String key(String typedUsername) {
        String key = Usernames.normalize(typedUsername).toLowerCase(Locale.ROOT);
        return key.length() > 100 ? key.substring(0, 100) : key;
    }

    private static String clean(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }
}
