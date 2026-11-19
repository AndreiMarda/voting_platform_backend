package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.AuditLogRepository;
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
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditLogTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionService sessions;
    @Autowired PollService polls;
    @Autowired AuditLogRepository audit;

    User admin, alice;
    MockHttpSession adminSession;

    private User save(Role role) {
        User u = new User();
        u.setUsername("Aud" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
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
        TestSupport.endRunningSession(sessions);
        adminSession = login(admin);
    }

    @Test
    void creatingAndDeletingUsersIsRecordedWithWhoDidIt() throws Exception {
        String created = mvc.perform(post("/api/users").session(adminSession).contentType("application/json")
                        .content("{\"username\":\"Ion Popescu\",\"password\":\"temp-pass-1\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"id\":(\\d+).*", "$1");

        mvc.perform(get("/api/audit").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("USER_CREATED"))
                .andExpect(jsonPath("$[0].actorName").value(admin.getUsername()))
                .andExpect(jsonPath("$[0].details", containsString("Ion Popescu")));

        mvc.perform(delete("/api/users/" + id).session(adminSession)).andExpect(status().isNoContent());

        mvc.perform(get("/api/audit").session(adminSession))
                .andExpect(jsonPath("$[0].action").value("USER_DELETED"))
                .andExpect(jsonPath("$[0].details", containsString("Ion Popescu")));
    }

    @Test
    void passwordResetAndChangeAreRecorded() throws Exception {
        mvc.perform(post("/api/users/" + alice.getId() + "/reset-password").session(adminSession))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/audit").session(adminSession))
                .andExpect(jsonPath("$[0].action").value("PASSWORD_RESET"))
                .andExpect(jsonPath("$[0].actorName").value(admin.getUsername()))
                .andExpect(jsonPath("$[0].details", containsString(alice.getUsername())));
        // the entry never contains the password itself
        mvc.perform(get("/api/audit").session(adminSession))
                .andExpect(jsonPath("$[0].details", org.hamcrest.Matchers.not(containsString("nordclujnapoca"))));
    }

    @Test
    void theWholeLifeOfASessionAndAVoteIsRecordedNewestFirst() throws Exception {
        mvc.perform(post("/api/sessions").session(adminSession).contentType("application/json")
                .content("{\"name\":\"Assembly\"}")).andExpect(status().isCreated());
        String poll = mvc.perform(post("/api/polls").session(adminSession).contentType("application/json")
                        .content("{\"title\":\"budget\",\"anonymous\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String pollId = poll.replaceAll(".*\"id\":(\\d+).*", "$1");
        mvc.perform(post("/api/polls/" + pollId + "/close").session(adminSession)).andExpect(status().isOk());
        mvc.perform(delete("/api/polls/" + pollId).session(adminSession)).andExpect(status().isNoContent());

        mvc.perform(get("/api/audit?limit=4").session(adminSession))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].action").value("POLL_DELETED"))
                .andExpect(jsonPath("$[0].details", containsString("Budget")))              // what was erased is still known
                .andExpect(jsonPath("$[0].details", containsString("anonymous")))
                .andExpect(jsonPath("$[1].action").value("POLL_CLOSED"))
                .andExpect(jsonPath("$[2].action").value("POLL_CREATED"))
                .andExpect(jsonPath("$[3].action").value("SESSION_STARTED"));

        var current = sessions.current().orElseThrow();
        mvc.perform(post("/api/sessions/" + current.id() + "/end").session(adminSession)).andExpect(status().isOk());
        mvc.perform(get("/api/audit").session(adminSession))
                .andExpect(jsonPath("$[0].action").value("SESSION_ENDED"));
    }

    @Test
    void loginsFailuresAndLogoutsAreRecorded() throws Exception {
        mvc.perform(post("/login").param("username", "Nobody Here").param("password", "x"));
        mvc.perform(get("/api/audit").session(adminSession))
                .andExpect(jsonPath("$[0].action").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$[0].actorName").value("Nobody Here"))
                .andExpect(jsonPath("$[0].actorId").doesNotExist());

        MockHttpSession aliceSession = login(alice);
        mvc.perform(get("/api/audit").session(adminSession))
                .andExpect(jsonPath("$[0].action").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$[0].actorName").value(alice.getUsername()));

        mvc.perform(post("/logout").session(aliceSession));
        mvc.perform(get("/api/audit").session(adminSession))
                .andExpect(jsonPath("$[0].action").value("LOGOUT"))
                .andExpect(jsonPath("$[0].actorName").value(alice.getUsername()));
    }

    @Test
    void theIpAddressIsRecorded() throws Exception {
        mvc.perform(post("/login").param("username", "Nobody Here").param("password", "x")
                .with(request -> { request.setRemoteAddr("10.99.1.2"); return request; }));
        mvc.perform(get("/api/audit").session(adminSession))
                .andExpect(jsonPath("$[0].ip").value("10.99.1.2"));
    }

    /** The point of the anonymous vote: the audit log must never reveal who voted in it. */
    @Test
    void castingAVoteIsNeverRecorded() throws Exception {
        PollView poll = TestSupport.poll(sessions, polls, admin.getId(), "Secret");
        long before = audit.count();

        polls.vote(poll.id(), alice.getId(), Choice.FOR);                                  // through the service
        mvc.perform(post("/api/polls/" + poll.id() + "/votes").session(login(alice))      // and through the API (rejected)
                .contentType("application/json").content("{\"choice\":\"AGAINST\"}"));

        // the only new entries are the login we did ourselves, nothing about voting
        assertThat(audit.findAll().stream().skip(before).map(a -> a.getAction().name()))
                .allMatch(action -> action.equals("LOGIN_SUCCESS"));
        assertThat(audit.findAll()).noneMatch(a -> a.getDetails() != null && a.getDetails().toLowerCase().contains("voted for"));
    }

    @Test
    void onlyAdminsCanReadTheAuditLog() throws Exception {
        mvc.perform(get("/api/audit").session(login(alice))).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    void theLogCanBeReadPageByPage() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login").param("username", "Paging " + i).param("password", "x"));
        }
        String firstPage = mvc.perform(get("/api/audit?limit=3").session(adminSession))
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        long lastId = Long.parseLong(firstPage.replaceAll(".*\"id\":(\\d+)[^{]*\\}\\]$", "$1"));

        mvc.perform(get("/api/audit?limit=3&beforeId=" + lastId).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", lessThan((int) lastId)));
    }

    @Test
    void theApiOffersNoWayToChangeOrRemoveEntries() throws Exception {
        mvc.perform(delete("/api/audit/1").session(adminSession)).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/audit").session(adminSession).contentType("application/json").content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
