package nord.cluj.voting.service;

import nord.cluj.voting.dto.UserDtos.CreateUserRequest;
import nord.cluj.voting.dto.UserDtos.UserView;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String defaultPassword;

    public UserService(UserRepository users, PasswordEncoder encoder,
                       @Value("${voting.default-password}") String defaultPassword) {
        this.users = users;
        this.encoder = encoder;
        this.defaultPassword = defaultPassword;
    }

    /** New accounts must change the temporary password the admin chose (must_change_password defaults to true). */
    @Transactional
    public UserView create(CreateUserRequest req) {
        if (users.findByUsernameIgnoreCase(req.username()).isPresent()) {
            throw usernameTaken();
        }
        User user = new User();
        user.setUsername(req.username());
        user.setPasswordHash(encoder.encode(req.password()));
        user.setRole(req.role());
        try {
            return UserView.of(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) { // lost a race against the unique index
            throw usernameTaken();
        }
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::of).toList();
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
        } catch (DataIntegrityViolationException e) {
            // Removing them would erase or break poll history (who voted, who created what).
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This user is part of existing polls and cannot be deleted");
        }
    }

    /** "Forgot password": puts the default password back and forces the user to choose a new one. */
    @Transactional
    public void resetPassword(Long id) {
        User user = find(id);
        user.setPasswordHash(encoder.encode(defaultPassword));
        user.setMustChangePassword(true);
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
    }

    private User find(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static ResponseStatusException usernameTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Username is already taken");
    }
}
