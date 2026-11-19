package nord.cluj.voting.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.service.AuditService;
import nord.cluj.voting.service.LoginAttemptService;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/** A wrong username or password: remember it, audit it, and say "locked" if this was the attempt that tripped the limit. */
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private final LoginAttemptService attempts;
    private final AuditService audit;

    public LoginFailureHandler(LoginAttemptService attempts, AuditService audit) {
        this.attempts = attempts;
        this.audit = audit;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String typed = request.getParameter("username");
        String ip = request.getRemoteAddr();

        attempts.recordFailure(typed, ip);
        audit.recordAs(null, typed, AuditAction.LOGIN_FAILED, "USER", null, "Failed login attempt");

        Optional<Duration> wait = attempts.blockedFor(typed, ip);
        if (wait.isPresent()) {
            audit.recordAs(null, typed, AuditAction.LOGIN_LOCKED, "USER", null,
                    "Too many failed attempts: login blocked for about " + LoginThrottleFilter.minutes(wait.get()) + " min");
            response.sendRedirect("/login.html?locked=" + LoginThrottleFilter.minutes(wait.get()));
        } else {
            response.sendRedirect("/login.html?error");
        }
    }
}
