package nord.cluj.voting.controller;

import jakarta.validation.Valid;
import nord.cluj.voting.dto.UserDtos.ChangePasswordRequest;
import nord.cluj.voting.dto.UserDtos.UserView;
import nord.cluj.voting.security.AuthUser;
import nord.cluj.voting.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record MeView(Long id, String username, String role, boolean mustChangePassword) {
    }

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    /** Who am I? Read from the database, so it is never stale (e.g. right after a password change). */
    @GetMapping("/me")
    public MeView me(@AuthenticationPrincipal AuthUser user) {
        UserView u = users.get(user.getId());
        return new MeView(u.id(), u.username(), u.role().name(), u.mustChangePassword());
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ChangePasswordRequest body) {
        users.changePassword(user.getId(), body.currentPassword(), body.newPassword());
    }
}
