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
            @NotBlank @Size(max = 100) String firstName,    // checked by PersonNames after normalisation
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotNull Role role,
            @Size(max = 254) String email) {     // optional: if empty, the user must add it at first login
    }

    /** A person creating their own account while registration is open. Email is required, the ID ONCR optional. */
    public record RegisterRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 254) String email,
            @Size(max = 20) String idOncr) {
    }

    /** The user's own details. Email is required; the ID ONCR is optional (empty = leave as it is). */
    public record UpdateProfileRequest(@NotBlank @Size(max = 254) String email,
                                       @Size(max = 20) String idOncr) {
    }

    public record SetStatusRequest(@NotNull Boolean enabled) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    /** Never includes the password hash. */
    public record UserView(Long id, String username, String firstName, String lastName, String displayName,
                           Role role, boolean enabled,
                           boolean mustChangePassword, Instant createdAt,
                           String email, String idOncr, boolean mustAddEmail, Instant lastLoginAt) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getUsername(), u.getFirstName(), u.getLastName(), u.getDisplayName(),
                    u.getRole(), u.isEnabled(),
                    u.isMustChangePassword(), u.getCreatedAt(),
                    u.getEmail(), u.getIdOncr(), u.isMustAddEmail(), u.getLastLoginAt());
        }
    }

    /** A user who is currently logged in (sent a heartbeat recently). */
    public record OnlineUserView(Long id, String username, String displayName, Role role, Instant lastSeenAt) {
        public static OnlineUserView of(User u) {
            return new OnlineUserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.getLastSeenAt());
        }
    }
}
