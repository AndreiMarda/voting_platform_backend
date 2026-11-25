package nord.cluj.voting.controller;

import nord.cluj.voting.service.PollIntegrityService;
import nord.cluj.voting.service.PollIntegrityService.IntegrityView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Verify integrity" of a poll's ballots. Open to every signed-in user: it shows only counts and a fingerprint. */
@RestController
@RequestMapping("/api/polls")
public class IntegrityController {

    private final PollIntegrityService integrity;

    public IntegrityController(PollIntegrityService integrity) {
        this.integrity = integrity;
    }

    @GetMapping("/{id}/integrity")
    public IntegrityView verify(@PathVariable Long id) {
        return integrity.verify(id);
    }
}
