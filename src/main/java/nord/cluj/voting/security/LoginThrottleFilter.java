package nord.cluj.voting.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nord.cluj.voting.service.LoginAttemptService;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Runs before the password is even checked: if the username or the IP has too many recent failures the login is
 * refused outright, so a blocked attacker cannot learn whether a guess was right.
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginAttemptService attempts;

    public LoginThrottleFilter(LoginAttemptService attempts) {
        this.attempts = attempts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/login".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Duration> wait = attempts.blockedFor(request.getParameter("username"), request.getRemoteAddr());
        if (wait.isPresent()) {
            response.sendRedirect("/login.html?locked=" + minutes(wait.get()));
            return;
        }
        chain.doFilter(request, response);
    }

    /** Whole minutes, rounded up, at least 1. */
    static long minutes(Duration wait) {
        return Math.max(1, (wait.toSeconds() + 59) / 60);
    }
}
