package nord.cluj.voting.security;

import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import nord.cluj.voting.service.Profiles;
import nord.cluj.voting.service.Usernames;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * People sign in with their username or with their email address. Something containing an "@" is looked up as an
 * email (it can never be a username: those are letters and spaces only). An email only works if exactly one account
 * uses it; if somehow several do, it is refused rather than guessing whose account it is.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String typed) throws UsernameNotFoundException {
        if (typed != null && typed.contains("@")) {
            List<User> matches = users.findByEmailIgnoreCase(Profiles.normalizeEmail(typed));
            if (matches.size() == 1) {
                return new AuthUser(matches.get(0));
            }
            throw new UsernameNotFoundException("Unknown user");
        }
        // same normalisation as at creation: trimmed, single spaces; case is ignored by the lookup
        return users.findByUsernameIgnoreCase(Usernames.normalize(typed))
                .map(AuthUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
