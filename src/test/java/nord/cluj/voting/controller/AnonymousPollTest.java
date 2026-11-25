package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.PollParticipationRepository;
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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AnonymousPollTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionService sessions;
    @Autowired PollService polls;
    @Autowired PollParticipationRepository participation;

    User admin, alice, bob;
    MockHttpSession adminSession, aliceSession, bobSession;

    private User save(Role role) {
        User u = new User();
        u.setUsername("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        u.setPasswordHash(encoder.encode("password-123"));
        u.setRole(role);
        u.setIdOncr(TestSupport.idOncr());
        u.setMustChangePassword(false);
        u.setLastSeenAt(Instant.now());
        return users.saveAndFlush(u);
    }

    private MockHttpSession login(User u) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login")
                        .param("username", u.getUsername()).param("password", "password-123"))
                .andReturn().getRequest().getSession(false);
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = save(Role.ADMIN);
        alice = save(Role.USER);
        bob = save(Role.USER);
        TestSupport.ensureTestSession(sessions, admin.getId());
        adminSession = login(admin);
        aliceSession = login(alice);
        bobSession = login(bob);
    }

    @Test
    void anAdminCanCreateAnAnonymousVoteThroughTheApi() throws Exception {
        mvc.perform(post("/api/polls").session(adminSession).contentType("application/json")
                        .content("{\"title\":\"secret topic\",\"anonymous\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anonymous").value(true))
                .andExpect(jsonPath("$.title").value("Secret topic"));

        mvc.perform(post("/api/polls").session(adminSession).contentType("application/json")
                        .content("{\"title\":\"normal topic\"}"))                      // flag omitted = not anonymous
                .andExpect(jsonPath("$.anonymous").value(false));
    }

    @Test
    void nobodyCanSeeWhoVotedInAnAnonymousVote() throws Exception {
        PollView poll = polls.create(admin.getId(), "Secret", null, true);
        polls.vote(poll.id(), alice.getId(), Choice.FOR);

        // not an admin, not a voter, not even the person who voted
        for (MockHttpSession s : new MockHttpSession[]{adminSession, aliceSession, bobSession}) {
            mvc.perform(get("/api/polls/" + poll.id() + "/participation").session(s))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(not(containsString(alice.getUsername()))));
        }
    }

    @Test
    void onlyTheNumberOfVotersIsShown() throws Exception {
        PollView poll = polls.create(admin.getId(), "Secret", null, true);
        polls.vote(poll.id(), alice.getId(), Choice.FOR);

        mvc.perform(get("/api/polls/" + poll.id()).session(bobSession))
                .andExpect(jsonPath("$.votedCount").value(1))
                .andExpect(jsonPath("$.eligibleCount", greaterThanOrEqualTo(2)))       // "1 of N have voted"
                .andExpect(jsonPath("$.hasVoted").value(false))                         // bob sees only his own state
                .andExpect(content().string(not(containsString(alice.getUsername()))));
        mvc.perform(get("/api/polls?sessionId=" + poll.sessionId()).session(adminSession))
                .andExpect(content().string(not(containsString(alice.getUsername()))));
    }

    @Test
    void theChoiceIsNotStoredNextToTheVoter() throws Exception {
        PollView poll = polls.create(admin.getId(), "Secret", null, true);
        polls.vote(poll.id(), alice.getId(), Choice.AGAINST);

        assertThat(participation.findUserIdsByPollId(poll.id())).containsExactly(alice.getId()); // she voted ...
        assertThat(participation.findChoice(poll.id(), alice.getId())).isEmpty();               // ... what stays unlinked

        mvc.perform(get("/api/polls/" + poll.id()).session(aliceSession))
                .andExpect(jsonPath("$.hasVoted").value(true))
                .andExpect(jsonPath("$.myChoice").value(nullValue()));
    }

    @Test
    void theVotesStillAddUpWhenTheVoteIsClosed() throws Exception {
        PollView poll = polls.create(admin.getId(), "Secret", null, true);
        polls.vote(poll.id(), alice.getId(), Choice.FOR);
        polls.vote(poll.id(), bob.getId(), Choice.AGAINST);
        polls.close(poll.id(), admin.getId());

        mvc.perform(get("/api/polls/" + poll.id() + "/results").session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tally.FOR").value(1))
                .andExpect(jsonPath("$.tally.AGAINST").value(1))
                .andExpect(jsonPath("$.total").value(2));
        // and the names stay hidden after closing too
        mvc.perform(get("/api/polls/" + poll.id() + "/participation").session(adminSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousVoteStillAllowsOneVotePerUserAndNoAdminVotes() throws Exception {
        PollView poll = polls.create(admin.getId(), "Secret", null, true);
        polls.vote(poll.id(), alice.getId(), Choice.FOR);

        mvc.perform(post("/api/polls/" + poll.id() + "/votes").session(aliceSession)
                .contentType("application/json").content("{\"choice\":\"AGAINST\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/polls/" + poll.id() + "/votes").session(adminSession)
                .contentType("application/json").content("{\"choice\":\"FOR\"}")).andExpect(status().isForbidden());
    }

    @Test
    void aNormalVoteStillShowsWhoVotedAndKeepsTheVotersOwnChoice() throws Exception {
        PollView poll = polls.create(admin.getId(), "Open ballot", null, false);
        polls.vote(poll.id(), alice.getId(), Choice.FOR);

        mvc.perform(get("/api/polls/" + poll.id() + "/participation").session(bobSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voted[0]").value(alice.getUsername()));
        mvc.perform(get("/api/polls/" + poll.id()).session(aliceSession))
                .andExpect(jsonPath("$.myChoice").value("FOR"));
    }
}
