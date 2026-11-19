package nord.cluj.voting.security;

import nord.cluj.voting.entity.LoginAttempt;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.LoginAttemptRepository;
import nord.cluj.voting.repository.UserRepository;
import nord.cluj.voting.service.Usernames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;

/** Defaults under test: 5 failures per username, 30 per IP (read from the configuration), within 15 minutes. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginThrottleTest {

    static final String RIGHT = "right-pass-1";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired LoginAttemptRepository attempts;

    @org.springframework.beans.factory.annotation.Value("${voting.login.max-failures-per-ip}") int maxPerIp;

    String username;
    String ip;   // a private address per test run, so real failures from 127.0.0.1 in the dev database never interfere

    @BeforeEach
    void setUp() {
        username = "Thr" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ip = "10.77." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(1, 255);
        newUser(username);
    }

    private User newUser(String name) {
        User u = new User();
        u.setUsername(name);
        u.setPasswordHash(encoder.encode(RIGHT));
        u.setRole(Role.USER);
        u.setMustChangePassword(false);
        return users.saveAndFlush(u);
    }

    private MvcResult login(String typedUsername, String password) throws Exception {
        return mvc.perform(post("/login").param("username", typedUsername).param("password", password)
                .with(request -> { request.setRemoteAddr(ip); return request; })).andReturn();
    }

    private static String redirect(MvcResult r) {
        return r.getResponse().getRedirectedUrl();
    }

    private static void assertLocked(MvcResult r) {
        assertThat(redirect(r)).startsWith("/login.html?locked=");
        assertThat(r.getRequest().getSession(false)).isNull();   // and certainly not signed in
    }

    @Test
    void fiveWrongPasswordsBlockTheUsernameEvenForTheRightPassword() throws Exception {
        for (int i = 1; i <= 4; i++) {
            assertThat(redirect(login(username, "wrong-" + i))).isEqualTo("/login.html?error");
        }
        assertLocked(login(username, "wrong-5"));        // the attempt that trips the limit says so straight away
        assertLocked(login(username, RIGHT));            // a correct password no longer helps
    }

    @Test
    void theBlockShowsHowManyMinutesToWait() throws Exception {
        for (int i = 0; i < 5; i++) login(username, "wrong");

        String url = redirect(login(username, RIGHT));
        long minutes = Long.parseLong(url.substring(url.indexOf('=') + 1));
        assertThat(minutes).isBetween(1L, 15L);
    }

    @Test
    void aCorrectLoginBeforeTheLimitResetsTheCounter() throws Exception {
        for (int i = 0; i < 4; i++) login(username, "wrong");
        assertThat(redirect(login(username, RIGHT))).isEqualTo("/");

        for (int i = 0; i < 4; i++) {                    // four more would have been the 8th overall
            assertThat(redirect(login(username, "wrong"))).isEqualTo("/login.html?error");
        }
    }

    @Test
    void unknownUsernamesAreBlockedExactlyLikeRealOnes() throws Exception {
        String ghost = "Ghost Person";
        for (int i = 1; i <= 4; i++) {
            assertThat(redirect(login(ghost, "x"))).isEqualTo("/login.html?error");
        }
        assertLocked(login(ghost, "x"));                 // same behaviour: blocking reveals nothing about real accounts
    }

    @Test
    void theBlockIsPerUsernameSoOtherPeopleCanStillLogIn() throws Exception {
        String other = "Oth" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        newUser(other);
        for (int i = 0; i < 5; i++) login(username, "wrong");

        assertThat(redirect(login(other, RIGHT))).isEqualTo("/");
    }

    @Test
    void differentSpellingsOfTheSameNameShareOneCounter() throws Exception {
        login("  " + username.toUpperCase() + " ", "wrong");
        login(username.toLowerCase(), "wrong");
        login(username, "wrong");
        login(" " + username, "wrong");
        assertLocked(login(username.toUpperCase(), "wrong"));
    }

    @Test
    void oneIpGuessingManyUsernamesIsBlockedToo() throws Exception {
        for (int i = 1; i < maxPerIp; i++) {
            assertThat(redirect(login("Guess" + i, "x"))).isEqualTo("/login.html?error");
        }
        assertLocked(login("Guess" + maxPerIp, "x"));
        assertLocked(login(username, RIGHT));            // even a real user with the right password, from that IP
    }

    @Test
    void failuresOlderThanTheWindowNoLongerCount() throws Exception {
        String key = Usernames.normalize(username).toLowerCase(Locale.ROOT);
        Instant old = Instant.now().minusSeconds(16 * 60);
        attempts.saveAllAndFlush(List.of(
                new LoginAttempt(key, ip, old), new LoginAttempt(key, ip, old), new LoginAttempt(key, ip, old),
                new LoginAttempt(key, ip, old), new LoginAttempt(key, ip, old)));

        assertThat(redirect(login(username, RIGHT))).isEqualTo("/");
    }

    @Test
    void blockedAttemptsAreNotCountedSoAnAttackerCannotExtendTheLock() throws Exception {
        for (int i = 0; i < 5; i++) login(username, "wrong");
        String key = Usernames.normalize(username).toLowerCase(Locale.ROOT);
        long before = attempts.findByUsernameKeyAndAttemptedAtAfterOrderByAttemptedAtAsc(key, Instant.now().minusSeconds(3600)).size();

        for (int i = 0; i < 10; i++) login(username, "wrong");     // hammering while blocked

        long after = attempts.findByUsernameKeyAndAttemptedAtAfterOrderByAttemptedAtAsc(key, Instant.now().minusSeconds(3600)).size();
        assertThat(after).isEqualTo(before).isEqualTo(5);
    }

    @Test
    void theHomePageLoginPortalStillWorksForANormalLogin() throws Exception {
        mvc.perform(post("/login").param("username", username).param("password", RIGHT)
                        .with(request -> { request.setRemoteAddr(ip); return request; }))
                .andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").param("username", username).param("password", "nope")
                        .with(request -> { request.setRemoteAddr(ip); return request; }))
                .andExpect(redirectedUrlPattern("/login.html?error"));
    }
}
