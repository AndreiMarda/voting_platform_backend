package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import nord.cluj.voting.service.PollIntegrityService;
import nord.cluj.voting.service.PollService;
import nord.cluj.voting.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
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

/** The seal made when a poll closes, and "verify integrity". */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PollIntegrityTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionService sessions;
    @Autowired PollService polls;
    @Autowired PollIntegrityService integrity;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    /** Raw SQL bypasses Hibernate, so forget what it has already loaded (a real tamperer is another transaction). */
    private void sql(String statement, Object... args) {
        em.flush();
        jdbc.update(statement, args);
        em.clear();
    }

    User admin, alice, bob, carol;
    MockHttpSession aliceSession;

    private User save(Role role) {
        User u = new User();
        u.setUsername("Int" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        u.setPasswordHash(encoder.encode("password-123"));
        u.setRole(role);
        u.setIdOncr(TestSupport.idOncr());
        u.setMustChangePassword(false);
        u.setLastSeenAt(Instant.now());
        return users.saveAndFlush(u);
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = save(Role.ADMIN);
        alice = save(Role.USER);
        bob = save(Role.USER);
        carol = save(Role.USER);
        aliceSession = (MockHttpSession) mvc.perform(post("/login")
                        .param("username", alice.getUsername()).param("password", "password-123"))
                .andReturn().getRequest().getSession(false);
        TestSupport.endRunningSession(sessions);
    }

    private long votedPoll() {
        TestSupport.ensureTestSession(sessions, admin.getId());
        long id = polls.create(admin.getId(), "Budget", null).id();
        polls.vote(id, alice.getId(), Choice.FOR);
        polls.vote(id, bob.getId(), Choice.AGAINST);
        polls.vote(id, carol.getId(), Choice.FOR);
        return id;
    }

    @Test
    void anOpenPollIsNotSealedYetButItsCountsAlreadyAddUp() throws Exception {
        long id = votedPoll();
        mvc.perform(get("/api/polls/" + id + "/integrity").session(aliceSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.ballots").value(3))
                .andExpect(jsonPath("$.voters").value(3))
                .andExpect(jsonPath("$.seal").doesNotExist());
    }

    @Test
    void closingSealsTheBallotsAndEveryoneCanVerifyThem() throws Exception {
        long id = votedPoll();
        polls.close(id, admin.getId());

        mvc.perform(get("/api/polls/" + id + "/integrity").session(aliceSession))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.ballots").value(3))
                .andExpect(jsonPath("$.seal").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
        mvc.perform(get("/api/polls/999999999/integrity").session(aliceSession)).andExpect(status().isNotFound());
        mvc.perform(get("/api/polls/" + id + "/integrity")).andExpect(status().isUnauthorized());
    }

    @Test
    void endingTheSessionSealsItsOpenPollsToo() {
        long id = votedPoll();
        sessions.end(sessions.current().orElseThrow().id());
        assertThat(integrity.verify(id).status()).isEqualTo(PollIntegrityService.Status.SEALED);
    }

    @Test
    void aChangedBallotBreaksTheSeal() {
        long id = votedPoll();
        polls.close(id, admin.getId());
        String seal = integrity.verify(id).seal();

        // someone edits a vote straight in the database
        sql("update ballots set choice = 'AGAINST' where id = (select id from ballots where poll_id = ? and choice = 'FOR' limit 1)", id);

        var result = integrity.verify(id);
        assertThat(result.status()).isEqualTo(PollIntegrityService.Status.BROKEN);
        assertThat(result.seal()).isEqualTo(seal);
        assertThat(result.message()).contains("changed afterwards");
    }

    @Test
    void aRemovedOrAddedBallotIsDetected() {
        long id = votedPoll();
        polls.close(id, admin.getId());

        sql("delete from ballots where id = (select id from ballots where poll_id = ? limit 1)", id);
        assertThat(integrity.verify(id).status()).isEqualTo(PollIntegrityService.Status.BROKEN);

        long other = votedPoll2();
        sql("insert into ballots (poll_id, choice) values (?, 'FOR')", other);   // stuffed ballot, nobody voted for it
        assertThat(integrity.verify(other).status()).isEqualTo(PollIntegrityService.Status.BROKEN);
    }

    private long votedPoll2() {
        TestSupport.ensureTestSession(sessions, admin.getId());
        long id = polls.create(admin.getId(), "Second", null).id();
        polls.vote(id, alice.getId(), Choice.FOR);
        return id;
    }

    @Test
    void pollsClosedBeforeSealsExistedGetTheirFirstSeal() {
        long id = votedPoll();
        polls.close(id, admin.getId());
        sql("update polls set seal = null, sealed_ballots = null where id = ?", id);   // as if closed long ago
        assertThat(integrity.verify(id).status()).isEqualTo(PollIntegrityService.Status.NOT_SEALED);

        assertThat(integrity.sealLegacyPolls()).isGreaterThanOrEqualTo(1);
        assertThat(integrity.verify(id).status()).isEqualTo(PollIntegrityService.Status.SEALED);
    }

    @Test
    void theSealDoesNotDependOnWhoVotedOrInWhatOrder() {
        // two polls with the same choices cast by different people / in a different order still verify on their own
        long a = votedPoll();
        polls.close(a, admin.getId());
        TestSupport.ensureTestSession(sessions, admin.getId());
        long b = polls.create(admin.getId(), "Other", null).id();
        polls.vote(b, carol.getId(), Choice.FOR);
        polls.vote(b, bob.getId(), Choice.AGAINST);
        polls.vote(b, alice.getId(), Choice.FOR);
        polls.close(b, admin.getId());
        assertThat(integrity.verify(a).status()).isEqualTo(PollIntegrityService.Status.SEALED);
        assertThat(integrity.verify(b).status()).isEqualTo(PollIntegrityService.Status.SEALED);
        assertThat(integrity.verify(a).seal()).isNotEqualTo(integrity.verify(b).seal());   // different poll id and ballot ids
    }
}
