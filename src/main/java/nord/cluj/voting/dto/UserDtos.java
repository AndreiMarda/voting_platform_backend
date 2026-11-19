package nord.cluj.voting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;

import java.time.Instant;

public final class UserDtos {

    private UserDtos() {
    }

    /**
     * The username is validated by {@code Usernames} after normalisation (trim, single spaces, capitals),
     * so it is only length-limited here. Password max is 72: BCrypt ignores anything beyond 72 bytes.
     */
    public record CreateUserRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotNull Role role) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    /** Never includes the password hash. */
    public record UserView(Long id, String username, Role role, boolean enabled,
                           boolean mustChangePassword, Instant createdAt) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getUsername(), u.getRole(), u.isEnabled(),
                    u.isMustChangePassword(), u.getCreatedAt());
        }
    }

    /** A user who is currently logged in (sent a heartbeat recently). */
    public record OnlineUserView(Long id, String username, Role role, Instant lastSeenAt) {
        public static OnlineUserView of(User u) {
            return new OnlineUserView(u.getId(), u.getUsername(), u.getRole(), u.getLastSeenAt());
        }
    }
}
