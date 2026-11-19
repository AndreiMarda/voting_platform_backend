package nord.cluj.voting.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** The "prod" profile (application-prod.yaml) turns on the HTTPS-only cookie and proxy-header handling. */
@SpringBootTest
@ActiveProfiles("prod")
class ProdProfileTest {

    @Autowired Environment env;

    @Test
    void sessionCookieIsSecureHttpOnlyAndSameSiteStrict() {
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        assertThat(env.getProperty("server.servlet.session.cookie.http-only", Boolean.class)).isTrue();
        assertThat(env.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("strict");
    }

    @Test
    void proxyHeadersAreHonoured() {
        assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
    }

}
