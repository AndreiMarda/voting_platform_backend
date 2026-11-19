package nord.cluj.voting.config;

import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the very first admin on startup, from ADMIN_USERNAME / ADMIN_PASSWORD (.env or environment).
 * Does nothing once any admin exists, or when the variables are not set. The account must change its
 * password at first login.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder,
                          @Value("${voting.bootstrap-admin.username:}") String username,
                          @Value("${voting.bootstrap-admin.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /** @return true if an admin was created */
    boolean bootstrap() {
        if (username.isBlank() || password.isBlank() || users.existsByRole(Role.ADMIN)) {
            return false;
        }
        if (!username.matches("\\p{L}{3,30}") || password.length() < 8 || password.length() > 72) {
            log.warn("ADMIN_USERNAME / ADMIN_PASSWORD ignored: username must be 3-30 letters, password 8-72 characters");
            return false;
        }
        if (users.findByUsernameIgnoreCase(username).isPresent()) {
            log.warn("ADMIN_USERNAME '{}' is already taken by a non-admin account; no admin was created", username);
            return false;
        }
        User admin = new User();
        admin.setUsername(username);
        admin.setPasswordHash(encoder.encode(password));
        admin.setRole(Role.ADMIN); // mustChangePassword stays true
        users.saveAndFlush(admin);
        log.info("Created first admin '{}'. Remove ADMIN_PASSWORD from .env now; it must be changed at first login.", username);
        return true;
    }
}
