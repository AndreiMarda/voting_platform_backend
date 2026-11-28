package nord.cluj.voting.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.repository.UserRepository;
import nord.cluj.voting.service.AuditService;
import nord.cluj.voting.service.LoginAttemptService;
import nord.cluj.voting.service.PresenceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

import java.io.IOException;
import java.time.Instant;

/** A correct login: forget the username's earlier failures, audit it, and go to the home page. */
public class LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final LoginAttemptService attempts;
    private final AuditService audit;
    private final UserRepository users;
    private final PresenceService presence;

    public LoginSuccessHandler(LoginAttemptService attempts, AuditService audit, UserRepository users,
                               PresenceService presence) {
        super("/");
        setAlwaysUseDefaultTargetUrl(true);
        this.attempts = attempts;
        this.audit = audit;
        this.users = users;
        this.presence = presence;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, jakarta.servlet.ServletException {
        attempts.recordSuccess(request.getParameter("username"));
        if (authentication.getPrincipal() instanceof AuthUser user) {
            if (!presence.admit(user.getId())) {   // the platform is full: no session for this person
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                response.sendRedirect("/login.html?full=1");
                return;
            }
            users.recordLogin(user.getId(), Instant.now());
            audit.recordAs(user.getId(), user.getUsername(), AuditAction.LOGIN_SUCCESS, "USER", user.getId(), "Logged in");
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
