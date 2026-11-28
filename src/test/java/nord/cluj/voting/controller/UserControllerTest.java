package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.service.SessionService;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import nord.cluj.voting.service.PollService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired PollService polls;
    @Autowired SessionService sessions;

    User admin;
    MockHttpSession adminSession;

    private static String word() {
        StringBuilder sb = new StringBuilder().append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        for (int i = 0; i < 7; i++) sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        return sb.toString();
    }

    /** A valid, already-normalised username: first name + last name, letters only. */
    private static String name() {
        return word() + " " + word();
    }

    private User save(String username, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode("password-123"));
        u.setRole(role);
        u.setIdOncr(TestSupport.idOncr());
        u.setMustChangePassword(false);
        return users.saveAndFlush(u);
    }

    private MockHttpSession login(String username, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").param("username", username).param("password", password))
                .andReturn().getRequest().getSession(false);
    }

    private static String body(String username, String password, String role) {
        return "{\"username\":\"%s\",\"firstName\":\"Test\",\"lastName\":\"Person\",\"password\":\"%s\",\"role\":\"%s\"}"
                .formatted(username, password, role);
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = save(name(), Role.ADMIN);
        adminSession = login(admin.getUsername(), "password-123");
    }

    @Test
    void adminCreatesUserWhoCanThenLogIn() throws Exception {
        String username = name();
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content(body(username, "temp-pass-1", "USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("$2"))));

        // stored as a BCrypt hash, never the plain password
        User stored = users.findByUsernameIgnoreCase(username).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2").isNotEqualTo("temp-pass-1");

        mvc.perform(post("/login").param("username", username).param("password", "temp-pass-1"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void nonAdminCannotCreateListOrDelete() throws Exception {
        User plain = save(name(), Role.USER);
        MockHttpSession s = login(plain.getUsername(), "password-123");

        mvc.perform(post("/api/users").session(s).contentType("application/json")
                .content(body(name(), "temp-pass-1", "USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").session(s)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/users/" + admin.getId()).session(s)).andExpect(status().isForbidden());
    }

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(post("/api/users").contentType("application/json")
                .content(body(name(), "temp-pass-1", "USER"))).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                .content(body("Ana", "temp-pass-1", "USER"))).andExpect(status().isBadRequest());      // one word only
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                .content(body("Ana Maria Ioana Popescu", "temp-pass-1", "USER"))).andExpect(status().isBadRequest()); // four words
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                .content(body("Maria2 Popescu", "temp-pass-1", "USER"))).andExpect(status().isBadRequest());   // digit
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                .content(body("Maria_x Popescu", "temp-pass-1", "USER"))).andExpect(status().isBadRequest());  // symbol
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                .content(body(name(), "short", "USER"))).andExpect(status().isBadRequest());           // short password
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                .content(body(name(), "temp-pass-1", "SUPERUSER"))).andExpect(status().isBadRequest()); // bad role
    }

    @Test
    void usernameIsTrimmedCollapsedAndCapitalised() throws Exception {
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content(body("  ion   mihai POPESCU ", "temp-pass-1", "USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("Ion Mihai Popescu"));

        // login normalises the same way, so any spelling of the name works
        mvc.perform(post("/login").param("username", "  ION mihai   popescu ").param("password", "temp-pass-1"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void twoOrThreeWordsAreRequired() throws Exception {
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content(body("Ana Maria Popescu", "temp-pass-1", "USER")))
                .andExpect(status().isCreated());                       // 2 first names + last name
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content(body("Ana", "temp-pass-1", "USER")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("first names")));
    }

    @Test
    void onlineListShowsOnlyEnabledUsersSeenRecently() throws Exception {
        User recent = save(name(), Role.USER);
        User stale = save(name(), Role.USER);
        User never = save(name(), Role.USER);
        User disabled = save(name(), Role.USER);
        recent.setLastSeenAt(Instant.now().minusSeconds(20));
        stale.setLastSeenAt(Instant.now().minusSeconds(60 * 10));
        disabled.setLastSeenAt(Instant.now());
        disabled.setEnabled(false);
        users.saveAllAndFlush(java.util.List.of(recent, stale, disabled));

        var hasItem = org.hamcrest.Matchers.hasItem(recent.getUsername());
        mvc.perform(get("/api/users/online").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem))
                .andExpect(jsonPath("$[*].username",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(stale.getUsername()))))
                .andExpect(jsonPath("$[*].username",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(never.getUsername()))))
                .andExpect(jsonPath("$[*].username",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(disabled.getUsername()))));
    }

    @Test
    void userDisappearsFromOnlineListWhenTheyLogOut() throws Exception {
        User alice = save(name(), Role.USER);
        MockHttpSession aliceSession = login(alice.getUsername(), "password-123");
        mvc.perform(post("/api/heartbeat").session(aliceSession)).andExpect(status().isNoContent());
        users.flush();

        mvc.perform(get("/api/users/online").session(adminSession))
                .andExpect(jsonPath("$[*].username", org.hamcrest.Matchers.hasItem(alice.getUsername())));

        mvc.perform(post("/logout").session(aliceSession));
        users.flush();

        mvc.perform(get("/api/users/online").session(adminSession))
                .andExpect(jsonPath("$[*].username",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(alice.getUsername()))));
    }

    @Test
    void onlineListIsAdminOnly() throws Exception {
        User plain = save(name(), Role.USER);
        mvc.perform(get("/api/users/online").session(login(plain.getUsername(), "password-123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void lettersWithDiacriticsAreAccepted() throws Exception {
        String username = "Ștefan" + name().substring(1);
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json; charset=UTF-8")
                        .content(body(username, "temp-pass-1", "USER").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andExpect(status().isCreated());
    }

    @Test
    void listNeverExposesHashes() throws Exception {
        mvc.perform(get("/api/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("$2"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("passwordHash"))));
    }

    @Test
    void adminDeletesUser() throws Exception {
        User victim = save(name(), Role.USER);
        mvc.perform(delete("/api/users/" + victim.getId()).session(adminSession))
                .andExpect(status().isNoContent());
        assertThat(users.findById(victim.getId())).isEmpty();
    }

    @Test
    void deletingUnknownUserIs404() throws Exception {
        mvc.perform(delete("/api/users/999999999").session(adminSession)).andExpect(status().isNotFound());
    }

    @Test
    void adminCannotDeleteThemselves() throws Exception {
        mvc.perform(delete("/api/users/" + admin.getId()).session(adminSession)).andExpect(status().isConflict());
        assertThat(users.findById(admin.getId())).isPresent();
    }

    @Test
    void userInExistingPollCannotBeDeleted() throws Exception {
        User voter = save(name(), Role.USER);
        voter.setLastSeenAt(Instant.now());
        users.saveAndFlush(voter);
        TestSupport.poll(sessions, polls, admin.getId(), "Budget"); // snapshot includes the online voter

        mvc.perform(delete("/api/users/" + voter.getId()).session(adminSession))
                .andExpect(status().isConflict());
    }

    /** Last on purpose: a failed insert poisons the shared test transaction. */
    @Test
    void duplicateUsernameIsRejectedCaseInsensitively() throws Exception {
        User existing = save(name(), Role.USER);
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content(body(existing.getUsername().toUpperCase(), "temp-pass-1", "USER")))
                .andExpect(status().isConflict());
    }
}
