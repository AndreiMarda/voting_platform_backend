package nord.cluj.voting.controller;

import nord.cluj.voting.repository.UserRepository;
import nord.cluj.voting.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** The frontend calls this periodically; it is how the server knows who is "online". */
@RestController
@RequestMapping("/api/heartbeat")
public class HeartbeatController {

    private final UserRepository users;

    public HeartbeatController(UserRepository users) {
        this.users = users;
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void beat(@AuthenticationPrincipal AuthUser user) {
        users.touch(user.getId(), Instant.now());
    }
}
