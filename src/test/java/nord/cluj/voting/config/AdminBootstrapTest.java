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
        assertThat(with("Admin1 Test", "long-enough-pass").bootstrap()).isFalse(); // digit in name
        assertThat(with("Maria Popescu", "short").bootstrap()).isFalse();             // short password
        assertThat(with("Maria", "long-enough-pass").bootstrap()).isFalse();          // one word only
        assertThat(with("A B C D", "long-enough-pass").bootstrap()).isFalse();        // four words
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
        assertThat(with("New Admin", "long-enough-pass").bootstrap()).isFalse();
        assertThat(users.findByUsernameIgnoreCase("New Admin")).isEmpty();
    }

    @Test
    void createsFirstAdminWithHashedPasswordAndForcedChange() {
        assumeFalse(users.existsByRole(Role.ADMIN), "database already has an admin");

        assertThat(with("  first   admin ", "long-enough-pass").bootstrap()).isTrue();

        User admin = users.findByUsernameIgnoreCase("First Admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(encoder.matches("long-enough-pass", admin.getPasswordHash())).isTrue();
        assertThat(admin.getPasswordHash()).doesNotContain("long-enough-pass");
    }
}
