package nord.cluj.voting.controller;

import nord.cluj.voting.TestSupport;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Self-registration from the login page, controlled by an admin switch. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RegistrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    User admin, alice;
    MockHttpSession adminSession, aliceSession;

    private User save(Role role) {
        User u = new User();
        u.setUsername("Reg" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
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

    private static String body(String username, String email) {
        return "{\"username\":\"" + username + "\",\"firstName\":\"ana\",\"lastName\":\"Ionescu\","
                + "\"password\":\"my-own-password\",\"email\":\"" + email + "\"}";
    }

    private void setOpen(boolean open) throws Exception {
        mvc.perform(put("/api/registration").session(adminSession).contentType("application/json")
                        .content("{\"open\":" + open + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(open));
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = save(Role.ADMIN);
        alice = save(Role.USER);
        adminSession = login(admin);
        aliceSession = login(alice);
        setOpen(false);   // start closed whatever the database says (rolled back after the test)
    }

    @Test
    void registrationIsClosedByDefaultAndTheLoginPageCanSeeIt() throws Exception {
        mvc.perform(get("/api/registration"))                       // no login needed
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));
        mvc.perform(post("/api/register").contentType("application/json").content(body("Ana Ionescu", "ana@example.com")))
                .andExpect(status().isForbidden());
        assertThat(users.findByUsernameIgnoreCase("Ana Ionescu")).isEmpty();
    }

    @Test
    void onlyAnAdminCanOpenOrCloseRegistration() throws Exception {
        mvc.perform(put("/api/registration").session(aliceSession).contentType("application/json").content("{\"open\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/registration").contentType("application/json").content("{\"open\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/registration")).andExpect(jsonPath("$.open").value(false));
    }

    @Test
    void whileOpenAnyoneCanCreateAnAccountAndSignInWithIt() throws Exception {
        setOpen(true);
        mvc.perform(get("/api/registration")).andExpect(jsonPath("$.open").value(true));

        mvc.perform(post("/api/register").contentType("application/json").content(body(" ana  IONESCU ", "Ana@Example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("Ana Ionescu"))
                .andExpect(jsonPath("$.displayName").value("Ana Ionescu"));

        User created = users.findByUsernameIgnoreCase("Ana Ionescu").orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.USER);              // never an admin
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getEmail()).isEqualTo("ana@example.com");
        assertThat(created.isMustChangePassword()).isFalse();            // they chose the password themselves
        assertThat(created.isMustAddEmail()).isFalse();
        assertThat(created.getIdOncr()).isNull();                        // can sign in, cannot vote yet

        mvc.perform(post("/login").param("username", "Ana Ionescu").param("password", "my-own-password"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/api/audit?action=USER_REGISTERED").session(adminSession))
                .andExpect(jsonPath("$[*].actorName", hasItem("Ana Ionescu")));
    }

    @Test
    void closingItStopsFurtherSignUps() throws Exception {
        setOpen(true);
        setOpen(false);
        mvc.perform(post("/api/register").contentType("application/json").content(body("Ana Ionescu", "ana@example.com")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit?action=REGISTRATION_CLOSED").session(adminSession))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void theSameRulesAsAdminCreatedAccountsApply() throws Exception {
        setOpen(true);
        String ok = "\"firstName\":\"Ana\",\"lastName\":\"Ionescu\",\"password\":\"my-own-password\",\"email\":\"a@example.com\"";
        // bad username, bad email, short password, missing email, bad / duplicate ID ONCR, taken username
        mvc.perform(post("/api/register").contentType("application/json")
                        .content("{\"username\":\"x\"," + ok + "}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/register").contentType("application/json")
                        .content(body("Ana Ionescu", "not-an-email"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/register").contentType("application/json")
                        .content("{\"username\":\"Ana Ionescu\",\"firstName\":\"Ana\",\"lastName\":\"Ionescu\",\"password\":\"short\","
                                + "\"email\":\"a@example.com\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/register").contentType("application/json")
                        .content("{\"username\":\"Ana Ionescu\",\"firstName\":\"Ana\",\"lastName\":\"Ionescu\","
                                + "\"password\":\"my-own-password\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/register").contentType("application/json")
                        .content("{\"username\":\"Ana Ionescu\"," + ok + ",\"idOncr\":\"bad id\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/register").contentType("application/json")
                        .content("{\"username\":\"" + alice.getUsername() + "\"," + ok + "}"))
                .andExpect(status().isBadRequest());     // not a valid name-shaped username
        assertThat(users.findByUsernameIgnoreCase("Ana Ionescu")).isEmpty();

        // an ID ONCR is optional but unique
        mvc.perform(post("/api/register").contentType("application/json")
                        .content("{\"username\":\"Ana Ionescu\"," + ok + ",\"idOncr\":\"zz12345\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/register").contentType("application/json")
                        .content("{\"username\":\"Dan Radu\"," + ok + ",\"idOncr\":\"ZZ12345\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/register").contentType("application/json")
                        .content("{\"username\":\"ana ionescu\"," + ok + "}"))
                .andExpect(status().isConflict());       // username already taken (any case)
        assertThat(users.findByUsernameIgnoreCase("Ana Ionescu").orElseThrow().getIdOncr()).isEqualTo("ZZ12345");
    }
}
