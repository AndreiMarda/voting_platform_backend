package nord.cluj.voting.controller;

import jakarta.validation.Valid;
import nord.cluj.voting.dto.UserDtos.CreateUserRequest;
import nord.cluj.voting.dto.UserDtos.OnlineUserView;
import nord.cluj.voting.dto.UserDtos.SetStatusRequest;
import nord.cluj.voting.dto.UserDtos.UserView;
import nord.cluj.voting.security.AuthUser;
import nord.cluj.voting.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Every route here is admin-only (see SecurityConfig: /api/users/**). */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest body) {
        return service.create(body);
    }

    @GetMapping
    public List<UserView> list() {
        return service.list();
    }

    /** "Forgot password": resets the user's password to the default one; they must change it at next login. */
    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable Long id) {
        service.resetPassword(id);
    }

    /** Activate or deactivate an account (not your own). */
    @PatchMapping("/{id}/status")
    public UserView setStatus(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id,
                              @Valid @RequestBody SetStatusRequest body) {
        return service.setEnabled(id, body.enabled(), admin.getId());
    }

    /** Who is logged in right now (active within the online window). */
    @GetMapping("/online")
    public List<OnlineUserView> online() {
        return service.listOnline();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        service.delete(id, admin.getId());
    }
}
