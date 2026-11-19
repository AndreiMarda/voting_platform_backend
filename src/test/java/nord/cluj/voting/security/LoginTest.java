package nord.cluj.voting.security;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Logs in against the real users table (rolled back after each test). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    String username;

    @BeforeEach
    void createUser() {
        username = "t" + UUID.randomUUID().toString().substring(0, 8);
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode("s3cret-pass"));
        u.setRole(Role.USER);
        u.setMustChangePassword(false);
        users.saveAndFlush(u);
    }

    @Test
    void apiRejectsAnonymousWith401() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void pagesRedirectAnonymousToLoginPortal() throws Exception {
        mvc.perform(get("/").accept("text/html")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login.html"));
    }

    @Test
    void loginPortalIsPublic() throws Exception {
        mvc.perform(get("/login.html")).andExpect(status().isOk());
    }

    @Test
    void correctCredentialsCreateASession() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/login")
                        .param("username", username).param("password", "s3cret-pass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void usernameIsCaseInsensitive() throws Exception {
        mvc.perform(post("/login").param("username", username.toUpperCase()).param("password", "s3cret-pass"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void usernameIsTrimmedBeforeLookup() throws Exception {
        mvc.perform(post("/login").param("username", "   " + username + "  ").param("password", "s3cret-pass"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/login").param("username", username).param("password", "nope"))
                .andExpect(redirectedUrl("/login.html?error"));
    }

    @Test
    void unknownUserIsRejected() throws Exception {
        mvc.perform(post("/login").param("username", "ghost").param("password", "s3cret-pass"))
                .andExpect(redirectedUrl("/login.html?error"));
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        User u = users.findByUsernameIgnoreCase(username).orElseThrow();
        u.setEnabled(false);
        users.saveAndFlush(u);

        mvc.perform(post("/login").param("username", username).param("password", "s3cret-pass"))
                .andExpect(redirectedUrl("/login.html?error"));
    }

    @Test
    void adminOnlyEndpointIsForbiddenForUsers() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/login")
                        .param("username", username).param("password", "s3cret-pass"))
                .andReturn().getRequest().getSession(false);

        mvc.perform(post("/api/polls").session(session).contentType("application/json")
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
