package nord.cluj.voting.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled (used for the live-update keep-alive). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
