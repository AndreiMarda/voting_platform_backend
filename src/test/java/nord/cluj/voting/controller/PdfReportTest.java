package nord.cluj.voting.controller;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
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

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PdfReportTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionService sessions;
    @Autowired PollService polls;

    User admin, alice, bob;
    SessionView session;

    private User save(Role role) {
        User u = new User();
        u.setUsername("Pdf" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
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

    private byte[] pdfFor(User viewer) throws Exception {
        return mvc.perform(get("/api/sessions/" + session.id() + "/report.pdf").session(login(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private static String text(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder all = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            all.append(extractor.getTextFromPage(page)).append('\n');
        }
        return all.toString().replaceAll("\\s+", " ");   // table cells wrap, so ignore line breaks
    }

    @Test
    void downloadsAsAPdfAttachment() throws Exception {
        mvc.perform(get("/api/sessions/" + session.id() + "/report.pdf").session(login(alice)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.matchesPattern(
                        "attachment; filename=\"session-" + session.id() + "-test-session\\.pdf\"")))
                .andExpect(content().contentType("application/pdf"));
        assertThat(new String(pdfFor(bob), 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void runningSessionListsOpenAndClosedVotes() throws Exception {
        PollView closed = polls.create(admin.getId(), "Buget 2027 ăîșț", null);
        polls.vote(closed.id(), alice.getId(), Choice.FOR);
        polls.vote(closed.id(), bob.getId(), Choice.AGAINST);
        polls.close(closed.id(), admin.getId());
        polls.create(admin.getId(), "Still running", null);

        String text = text(pdfFor(alice));       // a regular user can download it, mid-session
        assertThat(text).contains("Buget 2027 ăîșț").contains("Still running").contains("Closed").contains("Open");
        assertThat(text).doesNotContain(alice.getUsername()).doesNotContain(bob.getUsername());
    }

    @Test
    void deletedVotesStayInTheReport() throws Exception {
        PollView closed = polls.create(admin.getId(), "Erased closed vote", null);
        polls.vote(closed.id(), alice.getId(), Choice.FOR);
        polls.close(closed.id(), admin.getId());
        PollView open = polls.create(admin.getId(), "Erased open vote", null, true);
        polls.delete(closed.id());
        polls.delete(open.id());

        String text = text(pdfFor(bob));
        assertThat(text).contains("Erased closed vote").contains("Erased open vote")
                .contains("DELETED (was closed)").contains("DELETED (was open)")
                .contains("2 deleted");
    }

    @Test
    void worksAfterTheSessionEnded() throws Exception {
        PollView p = polls.create(admin.getId(), "Before the end", null);
        polls.vote(p.id(), alice.getId(), Choice.ABSTAIN);
        sessions.end(session.id());

        String text = text(pdfFor(admin));
        assertThat(text).contains("Before the end").contains("ended");
    }

    @Test
    void unknownSessionIs404AndAnonymousCallersAre401() throws Exception {
        mvc.perform(get("/api/sessions/999999999/report.pdf").session(login(alice))).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/" + session.id() + "/report.pdf")).andExpect(status().isUnauthorized());
    }
}
