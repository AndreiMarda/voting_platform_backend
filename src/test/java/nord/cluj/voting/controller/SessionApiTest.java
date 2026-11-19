package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.PollRepository;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SessionApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionService sessions;
    @Autowired PollService polls;
    @Autowired PollRepository pollRepo;

    User admin, alice;
    MockHttpSession adminSession, aliceSession;

    private User save(Role role) {
        User u = new User();
        u.setUsername("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        u.setPasswordHash(encoder.encode("password-123"));
        u.setRole(role);
        u.setMustChangePassword(false);
        u.setLastSeenAt(Instant.now());
        return users.saveAndFlush(u);
    }

    private MockHttpSession login(User u) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login")
                        .param("username", u.getUsername()).param("password", "password-123"))
                .andReturn().getRequest().getSession(false);
    }

    private static String json(String name) {
        return "{\"name\":" + (name == null ? "null" : "\"" + name + "\"") + "}";
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = save(Role.ADMIN);
        alice = save(Role.USER);
        adminSession = login(admin);
        aliceSession = login(alice);
        TestSupport.endRunningSession(sessions);   // start every test with no session running
    }

    @Test
    void currentIs204WhenNoSessionIsRunning() throws Exception {
        mvc.perform(get("/api/sessions/current").session(aliceSession)).andExpect(status().isNoContent());
    }

    @Test
    void adminStartsASessionAndEveryoneSeesItAsCurrent() throws Exception {
        mvc.perform(post("/api/sessions").session(adminSession).contentType("application/json")
                        .content(json("  General   assembly ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("General assembly"))          // trimmed, single spaces
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startedBy").value(admin.getUsername()))
                .andExpect(jsonPath("$.pollCount").value(0));

        mvc.perform(get("/api/sessions/current").session(aliceSession))           // a normal user can see it
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("General assembly"));
    }

    @Test
    void theNameIsMandatory() throws Exception {
        for (String bad : new String[]{null, "", "   "}) {
            mvc.perform(post("/api/sessions").session(adminSession).contentType("application/json").content(json(bad)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/sessions").session(adminSession).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/current").session(adminSession)).andExpect(status().isNoContent());
    }

    @Test
    void onlyAdminsCanStartOrEndSessions() throws Exception {
        mvc.perform(post("/api/sessions").session(aliceSession).contentType("application/json").content(json("X")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/sessions").contentType("application/json").content(json("X")))
                .andExpect(status().isUnauthorized());

        var s = sessions.start("Running", admin.getId());
        mvc.perform(post("/api/sessions/" + s.id() + "/end").session(aliceSession)).andExpect(status().isForbidden());
        assertThat(sessions.current()).isPresent();
    }

    @Test
    void onlyOneSessionCanRunAtATime() throws Exception {
        sessions.start("First", admin.getId());

        mvc.perform(post("/api/sessions").session(adminSession).contentType("application/json").content(json("Second")))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("First")));
    }

    @Test
    void aVoteCannotBeCreatedWithoutARunningSession() throws Exception {
        mvc.perform(post("/api/polls").session(adminSession).contentType("application/json")
                        .content("{\"title\":\"Orphan\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("Start a session")));
    }

    @Test
    void votesAreCreatedInsideTheRunningSession() throws Exception {
        var session = sessions.start("Assembly", admin.getId());

        mvc.perform(post("/api/polls").session(adminSession).contentType("application/json")
                        .content("{\"title\":\"Budget\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(session.id()));

        mvc.perform(get("/api/polls?sessionId=" + session.id()).session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Budget"));
        mvc.perform(get("/api/sessions/" + session.id()).session(aliceSession))
                .andExpect(jsonPath("$.pollCount").value(1));
    }

    @Test
    void sessionListFiltersKeepEachSessionsVotesApart() throws Exception {
        var first = sessions.start("First", admin.getId());
        polls.create(admin.getId(), "In first", null);
        sessions.end(first.id());
        var second = sessions.start("Second", admin.getId());
        polls.create(admin.getId(), "In second", null);

        mvc.perform(get("/api/polls?sessionId=" + first.id()).session(aliceSession))
                .andExpect(jsonPath("$[*].title", hasItem("In first")))
                .andExpect(jsonPath("$[*].title", not(hasItem("In second"))));
        mvc.perform(get("/api/polls?sessionId=" + second.id()).session(aliceSession))
                .andExpect(jsonPath("$[*].title", hasItem("In second")))
                .andExpect(jsonPath("$[*].title", not(hasItem("In first"))));
    }

    @Test
    void endingASessionClosesItsOpenVotesAndKeepsEverything() throws Exception {
        var session = sessions.start("Assembly", admin.getId());
        var poll = polls.create(admin.getId(), "Budget", null);
        polls.vote(poll.id(), alice.getId(), Choice.FOR);

        mvc.perform(post("/api/sessions/" + session.id() + "/end").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endedAt").exists())
                .andExpect(jsonPath("$.pollCount").value(1));

        // the vote was closed, can no longer be voted in, and is still there with its results
        mvc.perform(get("/api/polls/" + poll.id()).session(aliceSession))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.myChoice").value("FOR"));
        mvc.perform(get("/api/polls/" + poll.id() + "/results").session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tally.FOR").value(1));
        mvc.perform(get("/api/sessions/current").session(aliceSession)).andExpect(status().isNoContent());
        assertThat(pollRepo.existsById(poll.id())).isTrue();
    }

    @Test
    void anEndedSessionCannotBeEndedAgainAndANewOneCanStart() throws Exception {
        var session = sessions.start("Assembly", admin.getId());
        sessions.end(session.id());

        mvc.perform(post("/api/sessions/" + session.id() + "/end").session(adminSession))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/sessions").session(adminSession).contentType("application/json").content(json("Next")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/sessions").session(aliceSession))
                .andExpect(jsonPath("$[0].name").value("Next"))               // newest first
                .andExpect(jsonPath("$[*].name", hasItem("Assembly")));        // history is kept
    }

    @Test
    void unknownSessionIs404() throws Exception {
        mvc.perform(get("/api/sessions/999999999").session(aliceSession)).andExpect(status().isNotFound());
        mvc.perform(post("/api/sessions/999999999/end").session(adminSession)).andExpect(status().isNotFound());
    }
}
