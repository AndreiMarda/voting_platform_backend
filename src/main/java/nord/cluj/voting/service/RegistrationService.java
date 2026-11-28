package nord.cluj.voting.service;

import nord.cluj.voting.dto.UserDtos.RegisterRequest;
import nord.cluj.voting.dto.UserDtos.UserView;
import nord.cluj.voting.entity.AppSetting;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.AppSettingRepository;
import nord.cluj.voting.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-registration: while an admin has registration open, anyone can create their own (regular user) account
 * from the login page. Closed by default. Admin accounts can never be created this way.
 */
@Service
public class RegistrationService {

    static final String SETTING = "registration_open";

    private final AppSettingRepository settings;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final String defaultPassword;

    public RegistrationService(AppSettingRepository settings, UserRepository users, PasswordEncoder encoder,
                               AuditService audit, @Value("${voting.default-password}") String defaultPassword) {
        this.settings = settings;
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.defaultPassword = defaultPassword;
    }

    @Transactional(readOnly = true)
    public boolean isOpen() {
        return settings.findById(SETTING).map(s -> "true".equals(s.getValue())).orElse(false);
    }

    /** Opens or closes registration (admin). Only a real change is audited. */
    @Transactional
    public boolean setOpen(boolean open) {
        AppSetting setting = settings.findById(SETTING).orElseGet(() -> new AppSetting(SETTING, "false"));
        boolean wasOpen = "true".equals(setting.getValue());
        setting.setValue(Boolean.toString(open));
        settings.save(setting);
        if (wasOpen != open) {
            audit.record(open ? AuditAction.REGISTRATION_OPENED : AuditAction.REGISTRATION_CLOSED, "SETTINGS", null,
                    open ? "Registration opened: people can create their own account"
                         : "Registration closed");
        }
        return open;
    }

    /** Creates a regular, active user account from the person's own details. They chose their password, so no forced change. */
    @Transactional
    public UserView register(RegisterRequest req) {
        if (!isOpen()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration is closed right now");
        }
        String username = Usernames.normalize(req.username());
        if (!Usernames.isValid(username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, Usernames.RULE);
        }
        String firstName = PersonNames.normalize(req.firstName());
        String lastName = PersonNames.normalize(req.lastName());
        if (!PersonNames.isValid(firstName) || !PersonNames.isValid(lastName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PersonNames.RULE);
        }
        String email = Profiles.normalizeEmail(req.email());
        if (!Profiles.isValidEmail(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, Profiles.EMAIL_RULE);
        }
        if (!users.findByEmailIgnoreCase(email).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This email address is already used by another account");
        }
        if (req.password().equals(defaultPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a different password");
        }
        String idOncr = Profiles.normalizeId(req.idOncr());
        if (!idOncr.isEmpty()) {
            if (!Profiles.isValidId(idOncr)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, Profiles.ID_RULE);
            }
            if (users.findByIdOncr(idOncr).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This ID ONCR is already used by another account");
            }
        }
        if (users.findByUsernameIgnoreCase(username).isPresent()) {
            throw usernameTaken();
        }

        User user = new User();
        user.setUsername(username);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPasswordHash(encoder.encode(req.password()));
        user.setRole(Role.USER);
        user.setEmail(email);
        user.setIdOncr(idOncr.isEmpty() ? null : idOncr);
        user.setMustChangePassword(false);
        user.setMustAddEmail(false);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {   // lost a race against a unique index (username or ID ONCR)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username or ID ONCR is already taken");
        }
        audit.recordAs(user.getId(), user.getUsername(), AuditAction.USER_REGISTERED, "USER", user.getId(),
                "Registered the account \"" + user.getUsername() + "\" for " + user.getDisplayName());
        return UserView.of(user);
    }

    private static ResponseStatusException usernameTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Username is already taken");
    }
}
