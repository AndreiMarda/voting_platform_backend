package nord.cluj.voting.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.service.AuditService;
import nord.cluj.voting.service.LoginAttemptService;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

import java.io.IOException;

/** A correct login: forget the username's earlier failures, audit it, and go to the home page. */
public class LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final LoginAttemptService attempts;
    private final AuditService audit;

    public LoginSuccessHandler(LoginAttemptService attempts, AuditService audit) {
        super("/");
        setAlwaysUseDefaultTargetUrl(true);
        this.attempts = attempts;
        this.audit = audit;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, jakarta.servlet.ServletException {
        attempts.recordSuccess(request.getParameter("username"));
        if (authentication.getPrincipal() instanceof AuthUser user) {
            audit.recordAs(user.getId(), user.getUsername(), AuditAction.LOGIN_SUCCESS, "USER", user.getId(), "Logged in");
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
