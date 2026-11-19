package nord.cluj.voting.security;

import nord.cluj.voting.repository.UserRepository;
import nord.cluj.voting.service.Usernames;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // same normalisation as at creation: trimmed, single spaces; case is ignored by the lookup
        return users.findByUsernameIgnoreCase(Usernames.normalize(username))
                .map(AuthUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
