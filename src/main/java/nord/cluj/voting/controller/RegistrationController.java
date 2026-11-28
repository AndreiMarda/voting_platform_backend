package nord.cluj.voting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import nord.cluj.voting.dto.UserDtos.RegisterRequest;
import nord.cluj.voting.service.RegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-registration. Reading whether it is open and registering are public (the login page needs them);
 * opening and closing it is admin only (see SecurityConfig).
 */
@RestController
public class RegistrationController {

    public record RegistrationStatus(boolean open) {
    }

    public record SetRegistrationRequest(@NotNull Boolean open) {
    }

    public record Registered(String username, String displayName) {
    }

    private final RegistrationService service;

    public RegistrationController(RegistrationService service) {
        this.service = service;
    }

    @GetMapping("/api/registration")
    public RegistrationStatus status() {
        return new RegistrationStatus(service.isOpen());
    }

    @PutMapping("/api/registration")
    public RegistrationStatus set(@Valid @RequestBody SetRegistrationRequest body) {
        return new RegistrationStatus(service.setOpen(body.open()));
    }

    @PostMapping("/api/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Registered register(@Valid @RequestBody RegisterRequest body) {
        var user = service.register(body);
        return new Registered(user.username(), user.displayName());
    }
}
