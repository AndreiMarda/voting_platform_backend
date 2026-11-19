package nord.cluj.voting.controller;

import jakarta.validation.Valid;
import nord.cluj.voting.dto.PollDtos.CreatePollRequest;
import nord.cluj.voting.dto.PollDtos.ParticipationView;
import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.dto.PollDtos.ResultsView;
import nord.cluj.voting.dto.PollDtos.VoteRequest;
import nord.cluj.voting.security.AuthUser;
import nord.cluj.voting.service.PollService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/polls")
public class PollController {

    private final PollService service;

    public PollController(PollService service) {
        this.service = service;
    }

    /** Admin only (see SecurityConfig). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PollView create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreatePollRequest body) {
        return service.create(user.getId(), body.title(), body.description());
    }

    @GetMapping
    public List<PollView> list(@AuthenticationPrincipal AuthUser user) {
        return service.list(user.getId());
    }

    @GetMapping("/{id}")
    public PollView get(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return service.get(id, user.getId());
    }

    @PostMapping("/{id}/votes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void vote(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                     @Valid @RequestBody VoteRequest body) {
        service.vote(id, user.getId(), body.choice());
    }

    @GetMapping("/{id}/participation")
    public ParticipationView participation(@PathVariable Long id) {
        return service.participation(id);
    }

    /** Admin only (see SecurityConfig). */
    @PostMapping("/{id}/close")
    public PollView close(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return service.close(id, user.getId());
    }

    @GetMapping("/{id}/results")
    public ResultsView results(@PathVariable Long id) {
        return service.results(id);
    }
}
