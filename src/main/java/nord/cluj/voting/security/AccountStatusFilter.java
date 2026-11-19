package nord.cluj.voting.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * Re-checks the logged-in user against the database on every request, so a session never outlives
 * its account:
 * <ul>
 *   <li>user deleted or disabled -> session is ended;</li>
 *   <li>user must change their password (new or reset account) -> only the change-password flow,
 *       "who am I" and logout are allowed until they do.</li>
 * </ul>
 */
public class AccountStatusFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_WHILE_PASSWORD_CHANGE_PENDING = Set.of(
            "/api/auth/me", "/api/auth/change-password", "/change-password.html", "/logout", "/error");

    private final UserRepository users;

    public AccountStatusFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser principal) {
            Optional<User> current = users.findById(principal.getId());

            if (current.isEmpty() || !current.get().isEnabled()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                deny(request, response, HttpServletResponse.SC_UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "/login.html");
                return;
            }
            if (current.get().isMustChangePassword()
                    && !ALLOWED_WHILE_PASSWORD_CHANGE_PENDING.contains(request.getRequestURI())) {
                deny(request, response, HttpServletResponse.SC_FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
                        "/change-password.html");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** API callers get a JSON error; browsers get redirected to the right page. */
    private static void deny(HttpServletRequest request, HttpServletResponse response, int status,
                             String code, String redirectTo) throws IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"" + code + "\"}");
        } else {
            response.sendRedirect(redirectTo);
        }
    }
}
