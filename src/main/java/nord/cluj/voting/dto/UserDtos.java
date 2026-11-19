package nord.cluj.voting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;

import java.time.Instant;

public final class UserDtos {

    private UserDtos() {
    }

    /** Password max is 72: BCrypt ignores anything beyond 72 bytes. */
    public record CreateUserRequest(
            @NotBlank
            @Size(min = 3, max = 30)
            @Pattern(regexp = "\\p{L}+", message = "may only contain letters")
            String username,
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
}
