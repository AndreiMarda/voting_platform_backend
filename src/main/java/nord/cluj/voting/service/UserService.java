package nord.cluj.voting.service;

import nord.cluj.voting.dto.UserDtos.CreateUserRequest;
import nord.cluj.voting.dto.UserDtos.OnlineUserView;
import nord.cluj.voting.dto.UserDtos.UserView;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String defaultPassword;
    private final Duration onlineWindow;
    private final AuditService audit;

    public UserService(UserRepository users, PasswordEncoder encoder, AuditService audit,
                       @Value("${voting.default-password}") String defaultPassword,
                       @Value("${voting.online-window:PT2M}") Duration onlineWindow) {
        this.audit = audit;
        this.users = users;
        this.encoder = encoder;
        this.defaultPassword = defaultPassword;
        this.onlineWindow = onlineWindow;
    }

    /** New accounts must change the temporary password the admin chose (must_change_password defaults to true). */
    @Transactional
    public UserView create(CreateUserRequest req) {
        String username = Usernames.normalize(req.username());   // trimmed, single spaces, Capitalised Words
        if (!Usernames.isValid(username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, Usernames.RULE);
        }
        if (users.findByUsernameIgnoreCase(username).isPresent()) {   // case-insensitive
            throw usernameTaken();
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(req.password()));
        user.setRole(req.role());
        try {
            User saved = users.saveAndFlush(user);
            audit.record(AuditAction.USER_CREATED, "USER", saved.getId(),
                    "Created " + saved.getRole() + " account \"" + saved.getUsername() + "\"");
            return UserView.of(saved);
        } catch (DataIntegrityViolationException e) { // lost a race against the unique index
            throw usernameTaken();
        }
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::of).toList();
    }

    /** Users logged in right now: enabled, and their heartbeat is newer than the online window. */
    @Transactional(readOnly = true)
    public List<OnlineUserView> listOnline() {
        return users.findByEnabledTrueAndLastSeenAtAfter(Instant.now().minus(onlineWindow)).stream()
                .sorted(Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER))
                .map(OnlineUserView::of).toList();
    }

    @Transactional(readOnly = true)
    public UserView get(Long id) {
        return UserView.of(find(id));
    }

    @Transactional
    public void delete(Long id, Long requesterId) {
        if (id.equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot delete your own account");
        }
        User user = find(id);
        try {
            users.delete(user);
            users.flush();
            audit.record(AuditAction.USER_DELETED, "USER", id, "Deleted account \"" + user.getUsername() + "\"");
        } catch (DataIntegrityViolationException e) {
            // Removing them would erase or break poll history (who voted, who created what).
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This user is part of existing polls or sessions and cannot be deleted");
        }
    }

    /** "Forgot password": puts the default password back and forces the user to choose a new one. */
    @Transactional
    public void resetPassword(Long id) {
        User user = find(id);
        user.setPasswordHash(encoder.encode(defaultPassword));
        user.setMustChangePassword(true);
        audit.record(AuditAction.PASSWORD_RESET, "USER", id,
                "Password of \"" + user.getUsername() + "\" was reset to the default one");
    }

    @Transactional
    public void changePassword(Long id, String currentPassword, String newPassword) {
        User user = find(id);
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (newPassword.equals(currentPassword) || newPassword.equals(defaultPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose a new password different from the current and the default one");
        }
        user.setPasswordHash(encoder.encode(newPassword));
        user.setMustChangePassword(false);
        audit.record(AuditAction.PASSWORD_CHANGED, "USER", id, "Changed their own password");
    }

    private User find(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static ResponseStatusException usernameTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Username is already taken");
    }
}
