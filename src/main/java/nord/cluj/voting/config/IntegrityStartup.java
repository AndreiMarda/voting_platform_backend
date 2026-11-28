package nord.cluj.voting.config;

import nord.cluj.voting.service.PollIntegrityService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Gives polls that were closed before integrity seals existed their first seal. */
@Component
public class IntegrityStartup implements ApplicationRunner {

    private final PollIntegrityService integrity;

    public IntegrityStartup(PollIntegrityService integrity) {
        this.integrity = integrity;
    }

    @Override
    public void run(ApplicationArguments args) {
        integrity.sealLegacyPolls();
    }
}
