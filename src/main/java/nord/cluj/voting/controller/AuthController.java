package nord.cluj.voting.controller;

import jakarta.validation.Valid;
import nord.cluj.voting.dto.UserDtos.ChangePasswordRequest;
import nord.cluj.voting.dto.UserDtos.UpdateProfileRequest;
import nord.cluj.voting.dto.UserDtos.UserView;
import nord.cluj.voting.security.AuthUser;
import nord.cluj.voting.service.PollService;
import nord.cluj.voting.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record MeView(Long id, String username, String firstName, String lastName, String displayName,
                         String role, boolean mustChangePassword,
                         String email, String idOncr, boolean mustAddEmail) {
        static MeView of(UserView u) {
            return new MeView(u.id(), u.username(), u.firstName(), u.lastName(), u.displayName(),
                    u.role().name(), u.mustChangePassword(),
                    u.email(), u.idOncr(), u.mustAddEmail());
        }
    }

    private final UserService users;
    private final PollService polls;

    public AuthController(UserService users, PollService polls) {
        this.users = users;
        this.polls = polls;
    }

    /** Who am I? Read from the database, so it is never stale (e.g. right after a password change). */
    @GetMapping("/me")
    public MeView me(@AuthenticationPrincipal AuthUser user) {
        return MeView.of(users.get(user.getId()));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ChangePasswordRequest body) {
        users.changePassword(user.getId(), body.currentPassword(), body.newPassword());
    }

    /** The user's own email and ID ONCR. */
    @PutMapping("/profile")
    public MeView updateProfile(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody UpdateProfileRequest body) {
        MeView updated = MeView.of(users.updateProfile(user.getId(), body.email(), body.idOncr()));
        polls.enrollInOpenPolls(user.getId());   // someone who just got an ID ONCR can vote in the votes already open
        return updated;
    }
}
