package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Audit log search: from / to dates, user and action. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditFilterTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionService sessions;
    @Autowired nord.cluj.voting.repository.AuditLogRepository audit;

    User admin, alice;
    MockHttpSession adminSession;

    private User save(Role role, String first, String last) {
        User u = new User();
        u.setUsername("Flt" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        u.setFirstName(first);
        u.setLastName(last);
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
        admin = save(Role.ADMIN, null, null);
        alice = save(Role.USER, "Zelda", "Quillfeather");
        TestSupport.endRunningSession(sessions);
        adminSession = login(admin);
        login(alice);                                          // LOGIN_SUCCESS by alice
        sessions.start("Filter session", admin.getId());       // SESSION_STARTED by (nobody logged in here)
    }

    @Test
    void filtersByAction() throws Exception {
        mvc.perform(get("/api/audit?action=LOGIN_SUCCESS&limit=200").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", everyItem(is("LOGIN_SUCCESS"))))
                .andExpect(jsonPath("$[*].actorName", hasItem(alice.getUsername())));
        mvc.perform(get("/api/audit?action=SESSION_STARTED&limit=200").session(adminSession))
                .andExpect(jsonPath("$[*].action", everyItem(is("SESSION_STARTED"))))
                .andExpect(jsonPath("$[*].details", hasItem("Started session \"Filter session\"")));
        mvc.perform(get("/api/audit?action=NOT_AN_ACTION").session(adminSession))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filtersByUserNameOrByTheirFirstAndLastName() throws Exception {
        // by username, any case, partial
        mvc.perform(get("/api/audit?limit=200&user=" + alice.getUsername().toLowerCase().substring(2)).session(adminSession))
                .andExpect(jsonPath("$[*].actorName", everyItem(is(alice.getUsername()))))
                .andExpect(jsonPath("$.length()").value(1));
        // by the person's real name (the entry only stores the username)
        mvc.perform(get("/api/audit?limit=200&user=zelda quill").session(adminSession))
                .andExpect(jsonPath("$[*].actorName", everyItem(is(alice.getUsername()))))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/audit?limit=200&user=nobody-has-this-name").session(adminSession))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void percentAndUnderscoreAreSearchedLiterally() throws Exception {
        mvc.perform(get("/api/audit?limit=200&user=%25").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/audit?limit=200&user=_").session(adminSession))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void filtersByFromAndToDates() throws Exception {
        Instant now = Instant.now();
        String hourAgo = now.minus(Duration.ofHours(1)).toString();
        String hourAhead = now.plus(Duration.ofHours(1)).toString();

        mvc.perform(get("/api/audit?limit=200&from=" + hourAgo + "&to=" + hourAhead).session(adminSession))
                .andExpect(jsonPath("$[*].actorName", hasItem(alice.getUsername())));
        mvc.perform(get("/api/audit?limit=200&from=" + hourAhead).session(adminSession))      // nothing happens in the future
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/audit?limit=200&to=" + hourAgo + "&user=" + alice.getUsername()).session(adminSession))
                .andExpect(jsonPath("$.length()").value(0));                                   // alice only logged in just now
    }

    @Test
    void activatingAndDeactivatingUsersIsRecordedButNotShown() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/users/" + alice.getId() + "/status").session(adminSession)
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/audit?limit=200").session(adminSession))
                .andExpect(jsonPath("$[*].action", org.hamcrest.Matchers.not(hasItem("USER_DEACTIVATED"))));
        mvc.perform(get("/api/audit?limit=200&action=USER_DEACTIVATED").session(adminSession))
                .andExpect(jsonPath("$.length()").value(0));
        // it is still in the database
        org.assertj.core.api.Assertions.assertThat(audit.findAll())
                .anyMatch(a -> a.getAction() == nord.cluj.voting.entity.AuditAction.USER_DEACTIVATED);
    }

    @Test
    void filtersCombineAndPagingStillWorks() throws Exception {
        mvc.perform(get("/api/audit?limit=200&action=LOGIN_SUCCESS&user=" + alice.getUsername()).session(adminSession))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/audit?limit=200&action=SESSION_STARTED&user=" + alice.getUsername()).session(adminSession))
                .andExpect(jsonPath("$.length()").value(0));
        // paging with a filter: nothing is returned twice
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/login").param("username", "Nobody Atall").param("password", "x"));
        }
        String page = mvc.perform(get("/api/audit?limit=2&action=LOGIN_FAILED").session(adminSession))
                .andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse().getContentAsString();
        long lastId = ((Number) com.jayway.jsonpath.JsonPath.read(page, "$[1].id")).longValue();
        mvc.perform(get("/api/audit?limit=2&action=LOGIN_FAILED&beforeId=" + lastId).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", everyItem(is("LOGIN_FAILED"))));
    }
}
