package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.dto.PollDtos.PollView;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Someone who comes online after a vote was created can still vote in it. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LateJoinerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionService sessions;
    @Autowired PollService polls;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;

    User admin, early, late;
    MockHttpSession lateSession, adminSession;
    PollView poll;

    private User save(Role role, boolean online) {
        User u = new User();
        u.setUsername("Late" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        u.setPasswordHash(encoder.encode("password-123"));
        u.setRole(role);
        u.setIdOncr(TestSupport.idOncr());
        u.setMustChangePassword(false);
        if (online) u.setLastSeenAt(Instant.now());
        return users.saveAndFlush(u);
    }

    private MockHttpSession login(User u) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login")
                        .param("username", u.getUsername()).param("password", "password-123"))
                .andReturn().getRequest().getSession(false);
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = save(Role.ADMIN, true);
        early = save(Role.USER, true);
        late = save(Role.USER, false);                       // not online when the vote is created
        poll = TestSupport.poll(sessions, polls, admin.getId(), "Budget");
        lateSession = login(late);
        adminSession = login(admin);
    }

    @Test
    void aUserWhoWasOfflineCannotVoteUntilTheyComeOnline() throws Exception {
        mvc.perform(get("/api/polls/" + poll.id()).session(lateSession))
                .andExpect(jsonPath("$.eligible").value(false));
        mvc.perform(post("/api/polls/" + poll.id() + "/votes").session(lateSession)
                .contentType("application/json").content("{\"choice\":\"FOR\"}")).andExpect(status().isForbidden());
    }

    @Test
    void theirFirstHeartbeatAddsThemAndTheyCanVote() throws Exception {
        int before = polls.get(poll.id(), early.getId()).eligibleCount();

        mvc.perform(post("/api/heartbeat").session(lateSession)).andExpect(status().isNoContent());

        mvc.perform(get("/api/polls/" + poll.id()).session(lateSession))
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.eligibleCount").value(before + 1));       // "19 of 20" became "19 of 21"
        mvc.perform(post("/api/polls/" + poll.id() + "/votes").session(lateSession)
                .contentType("application/json").content("{\"choice\":\"FOR\"}")).andExpect(status().isNoContent());
    }

    @Test
    void repeatedHeartbeatsDoNotAddThemTwice() throws Exception {
        mvc.perform(post("/api/heartbeat").session(lateSession));
        int once = polls.get(poll.id(), early.getId()).eligibleCount();

        for (int i = 0; i < 3; i++) mvc.perform(post("/api/heartbeat").session(lateSession));

        assertThat(polls.get(poll.id(), early.getId()).eligibleCount()).isEqualTo(once);
    }

    @Test
    void aLateJoinerShowsUpInTheNotVotedListWhileOnline() throws Exception {
        mvc.perform(post("/api/heartbeat").session(lateSession));
        // The heartbeat updates last_seen_at with a direct SQL update. In production every request loads fresh data;
        // here the test shares one cached copy with the code under test, so drop it to see what a new request would.
        entityManager.clear();

        assertThat(polls.participation(poll.id()).notVoted()).contains(late.getUsername());
    }

    @Test
    void administratorsAreNeverEnrolled() throws Exception {
        User otherAdmin = save(Role.ADMIN, false);
        MockHttpSession session = login(otherAdmin);
        int before = polls.get(poll.id(), early.getId()).eligibleCount();

        mvc.perform(post("/api/heartbeat").session(session)).andExpect(status().isNoContent());

        assertThat(polls.get(poll.id(), early.getId()).eligibleCount()).isEqualTo(before);
        mvc.perform(get("/api/polls/" + poll.id()).session(session)).andExpect(jsonPath("$.eligible").value(false));
    }

    @Test
    void closedVotesAreNotJoinable() throws Exception {
        polls.close(poll.id(), admin.getId());

        mvc.perform(post("/api/heartbeat").session(lateSession));

        mvc.perform(get("/api/polls/" + poll.id()).session(lateSession)).andExpect(jsonPath("$.eligible").value(false));
    }

    @Test
    void aLateJoinerIsAddedToEveryOpenVoteAndAnonymousOnesToo() throws Exception {
        PollView second = polls.create(admin.getId(), "Second", null);
        PollView secret = polls.create(admin.getId(), "Secret", null, true);

        mvc.perform(post("/api/heartbeat").session(lateSession));

        for (PollView p : new PollView[]{poll, second, secret}) {
            mvc.perform(get("/api/polls/" + p.id()).session(lateSession)).andExpect(jsonPath("$.eligible").value(true));
        }
        polls.vote(secret.id(), late.getId(), Choice.AGAINST);
        assertThat(polls.get(secret.id(), late.getId()).hasVoted()).isTrue();
    }
}
