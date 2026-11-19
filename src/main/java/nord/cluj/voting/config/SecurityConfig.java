package nord.cluj.voting.config;

import jakarta.servlet.http.HttpServletRequest;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.repository.UserRepository;
import nord.cluj.voting.security.AccountStatusFilter;
import nord.cluj.voting.security.AuthUser;
import nord.cluj.voting.security.LoginFailureHandler;
import nord.cluj.voting.security.LoginSuccessHandler;
import nord.cluj.voting.security.LoginThrottleFilter;
import nord.cluj.voting.service.AuditService;
import nord.cluj.voting.service.LoginAttemptService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserRepository users,
                                            LoginAttemptService loginAttempts, AuditService audit) throws Exception {
        http
                .addFilterAfter(new AccountStatusFilter(users), AnonymousAuthenticationFilter.class)
                // brute-force protection: refuse a blocked username/IP before the password is checked
                .addFilterBefore(new LoginThrottleFilter(loginAttempts), UsernamePasswordAuthenticationFilter.class)
                // Session cookie is HttpOnly + SameSite=Strict (see application.yaml), which is what
                // protects the state-changing requests instead of CSRF tokens.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login.html", "/error", "/actuator/health").permitAll()
                        .requestMatchers("/api/users", "/api/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/audit", "/api/audit/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/sessions", "/api/sessions/*/end").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/polls/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/polls", "/api/polls/*/close").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")          // the portal
                        .loginProcessingUrl("/login")      // the form posts username + password here
                        .successHandler(new LoginSuccessHandler(loginAttempts, audit))   // -> "/"
                        .failureHandler(new LoginFailureHandler(loginAttempts, audit))   // -> "/login.html?error" or "?locked=N"
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .addLogoutHandler((request, response, authentication) -> {
                            // stop showing the user as online right away
                            if (authentication != null && authentication.getPrincipal() instanceof AuthUser u) {
                                users.clearLastSeen(u.getId());
                                audit.recordAs(u.getId(), u.getUsername(), AuditAction.LOGOUT, "USER", u.getId(), "Logged out");
                            }
                        })
                        .logoutSuccessUrl("/login.html?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                // API callers get a 401, not a redirect to the HTML login page
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        (HttpServletRequest r) -> r.getRequestURI().startsWith("/api/")));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
