package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** At most N people online at once (3 in this test; 60 by default). Admins are always let in. */
@SpringBootTest(properties = "voting.max-online=3")
@AutoConfigureMockMvc
@Transactional
class OnlineLimitTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    private User save(Role role, Instant lastSeen) {
        User u = new User();
        u.setUsername("Lim" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        u.setPasswordHash(encoder.encode("password-123"));
        u.setRole(role);
        u.setIdOncr(TestSupport.idOncr());
        u.setMustChangePassword(false);
        u.setLastSeenAt(lastSeen);
        return users.saveAndFlush(u);
    }

    private MockHttpSession login(User u) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login")
                        .param("username", u.getUsername()).param("password", "password-123"))
                .andReturn().getRequest().getSession(false);
    }

    private void fillThePlatform() {
        for (int i = 0; i < 3; i++) {
            save(Role.USER, Instant.now());
        }
    }

    @Test
    void aLoginIsRefusedWhenTheMaximumAreOnline() throws Exception {
        long before = users.countByEnabledTrueAndLastSeenAtAfter(Instant.now().minus(Duration.ofMinutes(2)));
        assertThat(before).isLessThan(3);                 // nobody else is online in the test database
        fillThePlatform();

        User late = save(Role.USER, null);
        mvc.perform(post("/login").param("username", late.getUsername()).param("password", "password-123"))
                .andExpect(redirectedUrl("/login.html?full=1"));
        assertThat(users.findById(late.getId()).orElseThrow().getLastSeenAt()).isNull();   // never counted as online
    }

    @Test
    void thereIsRoomAgainOnceSomeoneLogsOutOrGoesIdle() throws Exception {
        fillThePlatform();
        User late = save(Role.USER, null);
        mvc.perform(post("/login").param("username", late.getUsername()).param("password", "password-123"))
                .andExpect(redirectedUrl("/login.html?full=1"));

        users.findAll().stream().filter(u -> u.getLastSeenAt() != null).findFirst()
                .ifPresent(u -> { u.setLastSeenAt(null); users.saveAndFlush(u); });   // one person leaves
        mvc.perform(post("/login").param("username", late.getUsername()).param("password", "password-123"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void administratorsAreAlwaysLetIn() throws Exception {
        fillThePlatform();
        User admin = save(Role.ADMIN, null);
        mvc.perform(post("/login").param("username", admin.getUsername()).param("password", "password-123"))
                .andExpect(redirectedUrl("/"));
        MockHttpSession s = login(admin);
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk());
    }

    @Test
    void somebodyWhoWasIdleCannotComeBackIntoAFullPlatform() throws Exception {
        User idle = save(Role.USER, null);
        MockHttpSession session = login(idle);               // logs in while there is room
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());

        idle.setLastSeenAt(Instant.now().minus(Duration.ofMinutes(10)));   // went idle: no longer counted
        users.saveAndFlush(idle);
        fillThePlatform();                                    // and the platform filled up meanwhile

        mvc.perform(get("/api/polls").session(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("PLATFORM_FULL"));
        mvc.perform(post("/logout").session(session));        // leaving is always possible
    }

    @Test
    void peopleWhoAreOnlineKeepWorkingWhenThePlatformIsFull() throws Exception {
        User a = save(Role.USER, null);
        MockHttpSession session = login(a);                   // counted as online
        save(Role.USER, Instant.now());
        save(Role.USER, Instant.now());                       // now 3 online, the maximum

        mvc.perform(get("/api/polls").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/heartbeat").session(session)).andExpect(status().isNoContent());
    }
}
