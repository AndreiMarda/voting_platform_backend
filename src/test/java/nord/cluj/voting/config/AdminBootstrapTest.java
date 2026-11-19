package nord.cluj.voting.config;

import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@SpringBootTest
@Transactional
class AdminBootstrapTest {

    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    private AdminBootstrap with(String username, String password) {
        return new AdminBootstrap(users, encoder, username, password);
    }

    @Test
    void doesNothingWhenNotConfigured() {
        assertThat(with("", "").bootstrap()).isFalse();
    }

    @Test
    void rejectsInvalidCredentials() {
        assertThat(with("admin1", "long-enough-pass").bootstrap()).isFalse(); // digit in name
        assertThat(with("Maria", "short").bootstrap()).isFalse();             // short password
    }

    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        if (!users.existsByRole(Role.ADMIN)) {
            User a = new User();
            a.setUsername("existingadmin");
            a.setPasswordHash("x");
            a.setRole(Role.ADMIN);
            users.saveAndFlush(a);
        }
        assertThat(with("Newadmin", "long-enough-pass").bootstrap()).isFalse();
        assertThat(users.findByUsernameIgnoreCase("Newadmin")).isEmpty();
    }

    @Test
    void createsFirstAdminWithHashedPasswordAndForcedChange() {
        assumeFalse(users.existsByRole(Role.ADMIN), "database already has an admin");

        assertThat(with("Firstadmin", "long-enough-pass").bootstrap()).isTrue();

        User admin = users.findByUsernameIgnoreCase("firstadmin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(encoder.matches("long-enough-pass", admin.getPasswordHash())).isTrue();
        assertThat(admin.getPasswordHash()).doesNotContain("long-enough-pass");
    }
}
