package nord.cluj.voting.security;

import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordResetTest {

    @org.springframework.beans.factory.annotation.Value("${voting.default-password}") String defaultPassword;

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    User admin;
    User alice;
    MockHttpSession adminSession;

    private User save(Role role, String password, boolean mustChange) {
        User u = new User();
        u.setUsername("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        u.setMustChangePassword(mustChange);
        return users.saveAndFlush(u);
    }

    private MockHttpSession login(User u, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login")
                        .param("username", u.getUsername()).param("password", password))
                .andReturn().getRequest().getSession(false);
    }

    private static String change(String current, String next) {
        return "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next);
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = save(Role.ADMIN, "admin-pass-1", false);
        alice = save(Role.USER, "alice-pass-1", false);
        adminSession = login(admin, "admin-pass-1");
    }

    @Test
    void resetPutsDefaultPasswordBackAndForcesChange() throws Exception {
        mvc.perform(post("/api/users/" + alice.getId() + "/reset-password").session(adminSession))
                .andExpect(status().isNoContent());

        User reloaded = users.findById(alice.getId()).orElseThrow();
        assertThat(reloaded.isMustChangePassword()).isTrue();
        assertThat(encoder.matches(defaultPassword, reloaded.getPasswordHash())).isTrue();

        mvc.perform(post("/login").param("username", alice.getUsername()).param("password", "alice-pass-1"))
                .andExpect(redirectedUrl("/login.html?error")); // old password is gone
        mvc.perform(post("/login").param("username", alice.getUsername()).param("password", defaultPassword))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void onlyAdminsCanReset() throws Exception {
        MockHttpSession s = login(alice, "alice-pass-1");
        mvc.perform(post("/api/users/" + admin.getId() + "/reset-password").session(s))
                .andExpect(status().isForbidden());
    }

    @Test
    void resettingUnknownUserIs404() throws Exception {
        mvc.perform(post("/api/users/999999999/reset-password").session(adminSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void userWithDefaultPasswordIsLockedDownUntilTheyChangeIt() throws Exception {
        mvc.perform(post("/api/users/" + alice.getId() + "/reset-password").session(adminSession));
        MockHttpSession s = login(alice, defaultPassword);

        // blocked everywhere except the change-password flow
        mvc.perform(get("/api/polls").session(s)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/").session(s).accept("text/html"))
                .andExpect(redirectedUrl("/change-password.html"));
        mvc.perform(get("/change-password.html").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void changingPasswordUnlocksTheAccountAndSwapsCredentials() throws Exception {
        mvc.perform(post("/api/users/" + alice.getId() + "/reset-password").session(adminSession));
        MockHttpSession s = login(alice, defaultPassword);

        mvc.perform(post("/api/auth/change-password").session(s).contentType("application/json")
                        .content(change(defaultPassword, "brand-new-pass")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/polls").session(s)).andExpect(status().isOk()); // same session, now unlocked
        mvc.perform(get("/api/auth/me").session(s)).andExpect(jsonPath("$.mustChangePassword").value(false));

        mvc.perform(post("/login").param("username", alice.getUsername()).param("password", defaultPassword))
                .andExpect(redirectedUrl("/login.html?error"));
        mvc.perform(post("/login").param("username", alice.getUsername()).param("password", "brand-new-pass"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void changeIsRejectedWhenCurrentPasswordIsWrong() throws Exception {
        MockHttpSession s = login(alice, "alice-pass-1");
        mvc.perform(post("/api/auth/change-password").session(s).contentType("application/json")
                        .content(change("wrong-current", "brand-new-pass")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Current password is incorrect")));
    }

    @Test
    void newPasswordCannotBeTheDefaultTheSameOrTooShort() throws Exception {
        MockHttpSession s = login(alice, "alice-pass-1");
        for (String bad : new String[]{defaultPassword, "alice-pass-1", "short"}) {
            mvc.perform(post("/api/auth/change-password").session(s).contentType("application/json")
                    .content(change("alice-pass-1", bad))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void sessionEndsWhenAccountIsDisabled() throws Exception {
        MockHttpSession s = login(alice, "alice-pass-1");
        mvc.perform(get("/api/polls").session(s)).andExpect(status().isOk());

        alice.setEnabled(false);
        users.saveAndFlush(alice);

        mvc.perform(get("/api/polls").session(s)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionEndsWhenAccountIsDeleted() throws Exception {
        MockHttpSession s = login(alice, "alice-pass-1");
        users.delete(alice);
        users.flush();

        mvc.perform(get("/api/polls").session(s)).andExpect(status().isUnauthorized());
    }
}
