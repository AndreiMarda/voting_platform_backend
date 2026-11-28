package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.dto.SessionDtos.SessionView;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExportTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionService sessions;
    @Autowired PollService polls;

    User admin, alice, bob;
    SessionView session;

    private User save(Role role) {
        User u = new User();
        u.setUsername("Exp" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
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
    void setUp() {
        admin = save(Role.ADMIN);
        alice = save(Role.USER);
        bob = save(Role.USER);
        session = TestSupport.ensureTestSession(sessions, admin.getId());
    }

    private String csvFor(User viewer) throws Exception {
        byte[] body = mvc.perform(get("/api/sessions/" + session.id() + "/export.csv").session(login(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return new String(body, StandardCharsets.UTF_8);
    }

    private static List<String> lines(String csv) {
        return List.of(csv.split("\r\n"));
    }

    @Test
    void downloadsAsAnAttachmentWithAnExcelFriendlyEncoding() throws Exception {
        mvc.perform(get("/api/sessions/" + session.id() + "/export.csv").session(login(alice)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.matchesPattern(
                                "attachment; filename=\"session-" + session.id() + "-test-session\\.csv\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("text/csv"));

        String csv = csvFor(alice);
        assertThat(csv).startsWith("﻿Session,Vote,Type,Status,Created,Closed,Eligible voters,Voted,For,Against,Abstain");
    }

    @Test
    void closedVotesShowTotalsAndOpenVotesStayBlank() throws Exception {
        PollView closed = polls.create(admin.getId(), "Budget, 2027", null);
        polls.vote(closed.id(), alice.getId(), Choice.FOR);
        polls.vote(closed.id(), bob.getId(), Choice.AGAINST);
        polls.close(closed.id(), admin.getId());
        polls.create(admin.getId(), "Still running", null);

        List<String> lines = lines(csvFor(bob));
        String closedRow = lines.stream().filter(l -> l.contains("\"Budget, 2027\"")).findFirst().orElseThrow();
        String openRow = lines.stream().filter(l -> l.contains("Still running")).findFirst().orElseThrow();

        assertThat(closedRow).contains(",Normal,Closed,").endsWith(",1,1,0");
        assertThat(openRow).contains(",Normal,Open,").endsWith(",,,");          // totals stay hidden until closed
        assertThat(lines.indexOf(closedRow)).isLessThan(lines.indexOf(openRow)); // oldest first
    }

    @Test
    void anonymousVotesAreMarkedAndNeverContainNames() throws Exception {
        PollView secret = polls.create(admin.getId(), "Secret topic", null, true);
        polls.vote(secret.id(), alice.getId(), Choice.FOR);
        polls.close(secret.id(), admin.getId());

        String csv = csvFor(admin);
        String row = lines(csv).stream().filter(l -> l.contains("Secret topic")).findFirst().orElseThrow();
        assertThat(row).contains(",Anonymous,Closed,");
        assertThat(csv).doesNotContain(alice.getUsername()).doesNotContain(bob.getUsername());
    }

    @Test
    void titlesThatLookLikeFormulasAreDefused() throws Exception {
        polls.create(admin.getId(), "=HYPERLINK(\"http://evil\")", null);
        polls.create(admin.getId(), "-2+3", null);

        String csv = csvFor(admin);
        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"").contains(",'-2+3,");
        assertThat(lines(csv)).noneMatch(l -> l.contains(",=HYPERLINK"));
    }

    @Test
    void unknownSessionIs404AndAnonymousCallersAre401() throws Exception {
        mvc.perform(get("/api/sessions/999999999/export.csv").session(login(alice))).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/" + session.id() + "/export.csv")).andExpect(status().isUnauthorized());
    }
}
