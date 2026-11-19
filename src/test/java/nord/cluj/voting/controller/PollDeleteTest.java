package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.service.SessionService;
import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.BallotRepository;
import nord.cluj.voting.repository.PollEligibleVoterRepository;
import nord.cluj.voting.repository.PollParticipationRepository;
import nord.cluj.voting.repository.PollRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PollDeleteTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired PollService service;
    @Autowired SessionService sessionService;
    @Autowired PollRepository polls;
    @Autowired BallotRepository ballots;
    @Autowired PollParticipationRepository participation;
    @Autowired PollEligibleVoterRepository eligible;

    User admin, alice, bob;
    MockHttpSession adminSession;

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

    @BeforeEach
    void setUp() throws Exception {
        admin = save(Role.ADMIN);
        alice = save(Role.USER);
        bob = save(Role.USER);
        adminSession = login(admin);
    }

    private void assertEverythingGone(Long pollId) {
        assertThat(polls.existsById(pollId)).isFalse();
        assertThat(ballots.countByChoice(pollId)).isEmpty();
        assertThat(participation.findUserIdsByPollId(pollId)).isEmpty();
        assertThat(eligible.findUserIdsByPollId(pollId)).isEmpty();
    }

    @Test
    void adminCanDeleteAnOpenPollNobodyVotedIn() throws Exception {
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Fresh");

        mvc.perform(delete("/api/polls/" + poll.id()).session(adminSession)).andExpect(status().isNoContent());

        assertEverythingGone(poll.id());
        mvc.perform(get("/api/polls/" + poll.id()).session(adminSession)).andExpect(status().isNotFound());
    }

    @Test
    void adminCanDeleteAPollWhileItIsBeingVotedOn() throws Exception {
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "In progress");
        service.vote(poll.id(), alice.getId(), Choice.FOR);   // bob has not voted yet

        mvc.perform(delete("/api/polls/" + poll.id()).session(adminSession)).andExpect(status().isNoContent());

        assertEverythingGone(poll.id());
        // a voter who still has the page open gets a clean 404, not a crash
        MockHttpSession bobSession = login(bob);
        mvc.perform(post("/api/polls/" + poll.id() + "/votes").session(bobSession)
                .contentType("application/json").content("{\"choice\":\"FOR\"}")).andExpect(status().isNotFound());
    }

    @Test
    void adminCanDeleteAClosedPollWithResults() throws Exception {
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Done");
        service.vote(poll.id(), alice.getId(), Choice.FOR);
        service.vote(poll.id(), bob.getId(), Choice.AGAINST);
        service.close(poll.id(), admin.getId());

        mvc.perform(delete("/api/polls/" + poll.id()).session(adminSession)).andExpect(status().isNoContent());

        assertEverythingGone(poll.id());
        mvc.perform(get("/api/polls/" + poll.id() + "/results").session(adminSession)).andExpect(status().isNotFound());
    }

    @Test
    void deletingOnePollLeavesOthersUntouched() throws Exception {
        PollView doomed = TestSupport.poll(sessionService, service, admin.getId(), "Doomed");
        PollView kept = TestSupport.poll(sessionService, service, admin.getId(), "Kept");
        service.vote(kept.id(), alice.getId(), Choice.FOR);

        mvc.perform(delete("/api/polls/" + doomed.id()).session(adminSession)).andExpect(status().isNoContent());

        assertThat(polls.existsById(kept.id())).isTrue();
        assertThat(participation.findUserIdsByPollId(kept.id())).containsExactly(alice.getId());
        assertThat(ballots.countByChoice(kept.id())).hasSize(1);
    }

    @Test
    void aUserInAnExistingPollCannotBeDeleted() throws Exception {
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Temp");
        service.vote(poll.id(), alice.getId(), Choice.FOR);
        // while the poll exists, alice is part of it and cannot be deleted ...
        mvc.perform(delete("/api/users/" + alice.getId()).session(adminSession)).andExpect(status().isConflict());
    }

    @Test
    void deletingThePollFreesItsVotersForDeletion() throws Exception {
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Temp");
        service.vote(poll.id(), alice.getId(), Choice.FOR);

        mvc.perform(delete("/api/polls/" + poll.id()).session(adminSession)).andExpect(status().isNoContent());

        mvc.perform(delete("/api/users/" + alice.getId()).session(adminSession)).andExpect(status().isNoContent());
        assertThat(users.findById(alice.getId())).isEmpty();
    }

    @Test
    void normalUsersCannotDelete() throws Exception {
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Protected");
        mvc.perform(delete("/api/polls/" + poll.id()).session(login(alice))).andExpect(status().isForbidden());
        assertThat(polls.existsById(poll.id())).isTrue();
    }

    @Test
    void anonymousCannotDelete() throws Exception {
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Protected");
        mvc.perform(delete("/api/polls/" + poll.id())).andExpect(status().isUnauthorized());
        assertThat(polls.existsById(poll.id())).isTrue();
    }

    @Test
    void deletingUnknownPollIs404() throws Exception {
        mvc.perform(delete("/api/polls/999999999").session(adminSession)).andExpect(status().isNotFound());
    }
}
