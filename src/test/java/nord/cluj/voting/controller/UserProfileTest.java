package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import nord.cluj.voting.service.PollService;
import nord.cluj.voting.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Email, ID ONCR, last login and activating / deactivating users. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserProfileTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionService sessions;
    @Autowired PollService polls;

    User admin;
    MockHttpSession adminSession;

    private User save(Role role, String idOncr) {
        User u = new User();
        u.setUsername("p" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        u.setPasswordHash(encoder.encode("password-123"));
        u.setRole(role);
        u.setIdOncr(idOncr);
        u.setMustChangePassword(false);
        u.setLastSeenAt(Instant.now());
        return users.saveAndFlush(u);
    }

    private MockHttpSession login(User u) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login")
                        .param("username", u.getUsername()).param("password", "password-123"))
                .andReturn().getRequest().getSession(false);
    }

    private static String profile(String email, String idOncr) {
        return "{\"email\":\"" + email + "\"" + (idOncr == null ? "" : ",\"idOncr\":\"" + idOncr + "\"") + "}";
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = save(Role.ADMIN, null);
        adminSession = login(admin);
        TestSupport.endRunningSession(sessions);
    }

    // ---------- last login ----------

    @Test
    void aSuccessfulLoginIsRecordedAndShownToAdmins() throws Exception {
        User alice = save(Role.USER, null);
        assertThat(users.findById(alice.getId()).orElseThrow().getLastLoginAt()).isNull();

        login(alice);
        users.flush();
        mvc.perform(get("/api/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + alice.getId() + ")].lastLoginAt").isNotEmpty());
    }

    // ---------- activate / deactivate ----------

    @Test
    void anAdminCanDeactivateAndReactivateAUser() throws Exception {
        User alice = save(Role.USER, null);

        mvc.perform(patch("/api/users/" + alice.getId() + "/status").session(adminSession)
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        // a deactivated user can no longer sign in
        mvc.perform(post("/login").param("username", alice.getUsername()).param("password", "password-123"))
                .andExpect(redirectedUrl("/login.html?error"));

        mvc.perform(patch("/api/users/" + alice.getId() + "/status").session(adminSession)
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.enabled").value(true));
        assertThat(login(alice)).isNotNull();
    }

    @Test
    void deactivatingSignsTheUserOutAtOnce() throws Exception {
        User alice = save(Role.USER, null);
        MockHttpSession aliceSession = login(alice);
        mvc.perform(get("/api/auth/me").session(aliceSession)).andExpect(status().isOk());

        mvc.perform(patch("/api/users/" + alice.getId() + "/status").session(adminSession)
                .contentType("application/json").content("{\"enabled\":false}"));
        mvc.perform(get("/api/auth/me").session(aliceSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsCanChangeStatusAndNotOfThemselves() throws Exception {
        User alice = save(Role.USER, null);
        mvc.perform(patch("/api/users/" + admin.getId() + "/status").session(login(alice))
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/users/" + admin.getId() + "/status").session(adminSession)
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/users/999999999/status").session(adminSession)
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
    }

    // ---------- email ----------

    @Test
    void anAccountCreatedWithoutAnEmailMustAddOneAtLogin() throws Exception {
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content("{\"username\":\"Ion Popescu\",\"firstName\":\"Ion\",\"lastName\":\"Popescu\","
                                + "\"password\":\"temporary-1\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mustAddEmail").value(true))
                .andExpect(jsonPath("$.email").doesNotExist());

        User ion = users.findByUsernameIgnoreCase("Ion Popescu").orElseThrow();
        ion.setMustChangePassword(false);
        users.saveAndFlush(ion);
        MockHttpSession s = (MockHttpSession) mvc.perform(post("/login")
                        .param("username", "Ion Popescu").param("password", "temporary-1"))
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/api/polls").session(s)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("EMAIL_REQUIRED"));
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk());   // still allowed

        mvc.perform(put("/api/auth/profile").session(s).contentType("application/json")
                        .content(profile("Ion@Example.com", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ion@example.com"))
                .andExpect(jsonPath("$.mustAddEmail").value(false));
        mvc.perform(get("/api/polls").session(s)).andExpect(status().isOk());
    }

    @Test
    void anAdminMayGiveTheEmailAtCreationAndThenNothingIsForced() throws Exception {
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content("{\"username\":\"Ana Ionescu\",\"firstName\":\"Ana\",\"lastName\":\"Ionescu\",\"password\":\"temporary-1\",\"role\":\"USER\","
                                + "\"email\":\"ana@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ana@example.com"))
                .andExpect(jsonPath("$.mustAddEmail").value(false));
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content("{\"username\":\"Dan Radu\",\"firstName\":\"Dan\",\"lastName\":\"Radu\",\"password\":\"temporary-1\",\"role\":\"USER\","
                                + "\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- signing in with the email ----------

    @Test
    void peopleCanSignInWithTheirEmailInsteadOfTheUsername() throws Exception {
        User alice = save(Role.USER, null);
        alice.setEmail("alice.login@example.com");
        users.saveAndFlush(alice);

        mvc.perform(post("/login").param("username", "alice.login@example.com").param("password", "password-123"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").param("username", "  Alice.Login@EXAMPLE.com ").param("password", "password-123"))
                .andExpect(redirectedUrl("/"));                                   // any case, surrounding spaces ignored
        mvc.perform(post("/login").param("username", alice.getUsername()).param("password", "password-123"))
                .andExpect(redirectedUrl("/"));                                   // the username still works
        mvc.perform(post("/login").param("username", "alice.login@example.com").param("password", "wrong"))
                .andExpect(redirectedUrl("/login.html?error"));
        mvc.perform(post("/login").param("username", "nobody@example.com").param("password", "password-123"))
                .andExpect(redirectedUrl("/login.html?error"));
    }

    @Test
    void anEmailSharedByTwoAccountsCannotBeUsedToSignIn() throws Exception {
        User a = save(Role.USER, null);
        User b = save(Role.USER, null);
        a.setEmail("shared@example.com");
        b.setEmail("shared@example.com");
        users.saveAndFlush(a);
        users.saveAndFlush(b);

        mvc.perform(post("/login").param("username", "shared@example.com").param("password", "password-123"))
                .andExpect(redirectedUrl("/login.html?error"));                  // not clear whose account it is
        mvc.perform(post("/login").param("username", a.getUsername()).param("password", "password-123"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void anEmailAddressCanOnlyBelongToOneAccount() throws Exception {
        User alice = save(Role.USER, null);
        User bob = save(Role.USER, null);
        mvc.perform(put("/api/auth/profile").session(login(alice)).contentType("application/json")
                        .content(profile("one@example.com", null)))
                .andExpect(status().isOk());

        mvc.perform(put("/api/auth/profile").session(login(bob)).contentType("application/json")
                        .content(profile("ONE@example.com", null)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/auth/profile").session(login(alice)).contentType("application/json")
                        .content(profile("one@example.com", null)))
                .andExpect(status().isOk());                                      // keeping your own is fine
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content("{\"username\":\"Dan Radu\",\"firstName\":\"Dan\",\"lastName\":\"Radu\","
                                + "\"password\":\"temporary-1\",\"role\":\"USER\",\"email\":\"one@example.com\"}"))
                .andExpect(status().isConflict());
    }

    // ---------- first and last name ----------

    @Test
    void usersHaveAFirstAndLastNameShownInsteadOfTheUsername() throws Exception {
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content("{\"username\":\"Ana Ionescu\",\"firstName\":\"  ana  maria \",\"lastName\":\"popa-ionescu\","
                                + "\"password\":\"temporary-1\",\"role\":\"USER\",\"email\":\"ana@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("Ana Ionescu"))
                .andExpect(jsonPath("$.firstName").value("Ana Maria"))
                .andExpect(jsonPath("$.lastName").value("Popa-Ionescu"))
                .andExpect(jsonPath("$.displayName").value("Ana Maria Popa-Ionescu"));

        // she still signs in with the username
        MockHttpSession s = (MockHttpSession) mvc.perform(post("/login")
                        .param("username", "Ana Ionescu").param("password", "temporary-1"))
                .andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(s)).andExpect(jsonPath("$.displayName").value("Ana Maria Popa-Ionescu"));
    }

    @Test
    void firstAndLastNameAreRequiredAndMustLookLikeNames() throws Exception {
        String base = "\"username\":\"Dan Radu\",\"password\":\"temporary-1\",\"role\":\"USER\"";
        mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content("{" + base + "}"))
                .andExpect(status().isBadRequest());
        for (String bad : new String[]{"", "  ", "R2D2", "Dan!", "-Dan", "x".repeat(61)}) {
            mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                            .content("{" + base + ",\"firstName\":\"" + bad + "\",\"lastName\":\"Radu\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void participationListsPeopleByTheirNamesNotTheirUsernames() {
        User named = save(Role.USER, TestSupport.idOncr());
        named.setFirstName("Maria");
        named.setLastName("Pop");
        users.saveAndFlush(named);
        TestSupport.ensureTestSession(sessions, admin.getId());
        var poll = polls.create(admin.getId(), "Budget", null);

        assertThat(polls.participation(poll.id()).notVoted()).contains("Maria Pop")
                .doesNotContain(named.getUsername());
        polls.vote(poll.id(), named.getId(), Choice.FOR);
        assertThat(polls.participation(poll.id()).voted()).contains("Maria Pop");
    }

    // ---------- ID ONCR ----------

    @Test
    void theIdOncrMustBeTwoCapitalLettersAndDigitsMaxSevenCharacters() throws Exception {
        User alice = save(Role.USER, null);
        MockHttpSession s = login(alice);

        for (String bad : new String[]{"A1", "ABC12", "12AB", "AB123456", "AB 12", "AB-12", "AB"}) {
            mvc.perform(put("/api/auth/profile").session(s).contentType("application/json")
                            .content(profile("a@example.com", bad)))
                    .andExpect(status().isBadRequest());
        }
        for (String good : new String[]{"AB1", "ab12", " CD12345 "}) {
            mvc.perform(put("/api/auth/profile").session(s).contentType("application/json")
                            .content(profile("a@example.com", good)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.idOncr").value(good.trim().toUpperCase()));
        }
    }

    @Test
    void theIdOncrIsUnique() throws Exception {
        User alice = save(Role.USER, null);
        save(Role.USER, "ZZ99999");

        mvc.perform(put("/api/auth/profile").session(login(alice)).contentType("application/json")
                        .content(profile("a@example.com", "zz99999")))
                .andExpect(status().isConflict());
        // keeping your own ID is fine
        mvc.perform(put("/api/auth/profile").session(login(alice)).contentType("application/json")
                        .content(profile("a@example.com", "ZZ99998")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/auth/profile").session(login(alice)).contentType("application/json")
                        .content(profile("new@example.com", "ZZ99998")))
                .andExpect(status().isOk());
    }

    @Test
    void emptyIdLeavesTheCurrentOneAndAnEmailIsRequired() throws Exception {
        User alice = save(Role.USER, "ZZ11111");
        MockHttpSession s = login(alice);

        mvc.perform(put("/api/auth/profile").session(s).contentType("application/json")
                        .content(profile("a@example.com", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idOncr").value("ZZ11111"));
        mvc.perform(put("/api/auth/profile").session(s).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutAnIdOncrAUserIsNotEligibleAndCannotVote() {
        User withId = save(Role.USER, "ZZ22222");
        User withoutId = save(Role.USER, null);
        TestSupport.ensureTestSession(sessions, admin.getId());
        var poll = polls.create(admin.getId(), "Budget", null);

        assertThat(polls.get(poll.id(), withId.getId()).eligible()).isTrue();
        assertThat(polls.get(poll.id(), withoutId.getId()).eligible()).isFalse();
        assertThatThrownBy(() -> polls.vote(poll.id(), withoutId.getId(), Choice.FOR))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ID ONCR");
    }

    @Test
    void addingTheIdOncrWhileAVoteIsOpenLetsTheUserVote() throws Exception {
        User alice = save(Role.USER, null);
        TestSupport.ensureTestSession(sessions, admin.getId());
        var poll = polls.create(admin.getId(), "Budget", null);
        assertThat(polls.get(poll.id(), alice.getId()).eligible()).isFalse();

        mvc.perform(put("/api/auth/profile").session(login(alice)).contentType("application/json")
                        .content(profile("a@example.com", "ZZ33333")))
                .andExpect(status().isOk());

        assertThat(polls.get(poll.id(), alice.getId()).eligible()).isTrue();
        polls.vote(poll.id(), alice.getId(), Choice.FOR);
    }
}
