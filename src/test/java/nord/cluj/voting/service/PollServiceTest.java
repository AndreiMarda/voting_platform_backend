package nord.cluj.voting.service;

import nord.cluj.voting.dto.PollDtos.ParticipationView;
import nord.cluj.voting.dto.PollDtos.PollView;
import nord.cluj.voting.entity.Choice;
import nord.cluj.voting.entity.Role;
import nord.cluj.voting.entity.User;
import nord.cluj.voting.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Needs the Postgres database from application.yaml. Every test is rolled back. */
@SpringBootTest
@Transactional
class PollServiceTest {

    @Autowired PollService service;
    @Autowired UserRepository users;

    private User newUser(Role role, boolean online) {
        User u = new User();
        u.setUsername("t" + UUID.randomUUID().toString().substring(0, 8));
        u.setPasswordHash("x");
        u.setRole(role);
        if (online) u.setLastSeenAt(Instant.now());
        return users.saveAndFlush(u);
    }

    private static void assertStatus(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    @Test
    void userCanVoteOnlyOnce() {
        User admin = newUser(Role.ADMIN, true);
        User alice = newUser(Role.USER, true);
        PollView poll = service.create(admin.getId(), "Budget 2027", null);

        service.vote(poll.id(), alice.getId(), Choice.FOR);

        assertStatus(() -> service.vote(poll.id(), alice.getId(), Choice.AGAINST), HttpStatus.CONFLICT);
    }

    @Test
    void voterSeesOwnChoiceAndNobodyElseDoes() {
        User admin = newUser(Role.ADMIN, true);
        User alice = newUser(Role.USER, true);
        User bob = newUser(Role.USER, true);
        PollView poll = service.create(admin.getId(), "Budget 2027", null);

        assertThat(service.get(poll.id(), alice.getId()).myChoice()).isNull();
        service.vote(poll.id(), alice.getId(), Choice.AGAINST);

        PollView forAlice = service.get(poll.id(), alice.getId());
        assertThat(forAlice.hasVoted()).isTrue();
        assertThat(forAlice.myChoice()).isEqualTo(Choice.AGAINST);

        PollView forBob = service.get(poll.id(), bob.getId());
        assertThat(forBob.hasVoted()).isFalse();
        assertThat(forBob.myChoice()).isNull();
        assertThat(service.get(poll.id(), admin.getId()).myChoice()).isNull();
    }

    @Test
    void offlineUserIsNotEligible() {
        User admin = newUser(Role.ADMIN, true);
        User offline = newUser(Role.USER, false);
        PollView poll = service.create(admin.getId(), "Budget 2027", null);

        assertStatus(() -> service.vote(poll.id(), offline.getId(), Choice.FOR), HttpStatus.FORBIDDEN);
    }

    @Test
    void everyoneSeesWhoVotedButNotWhat() {
        User admin = newUser(Role.ADMIN, true);
        User alice = newUser(Role.USER, true);
        User bob = newUser(Role.USER, true);
        PollView poll = service.create(admin.getId(), "Budget 2027", null);

        service.vote(poll.id(), alice.getId(), Choice.FOR);

        ParticipationView p = service.participation(poll.id());
        assertThat(p.voted()).containsExactly(alice.getUsername());
        assertThat(p.notVoted()).containsExactlyInAnyOrder(admin.getUsername(), bob.getUsername());
    }

    @Test
    void resultsAreHiddenUntilClosedThenShowTotals() {
        User admin = newUser(Role.ADMIN, true);
        User alice = newUser(Role.USER, true);
        User bob = newUser(Role.USER, true);
        PollView poll = service.create(admin.getId(), "Budget 2027", null);
        service.vote(poll.id(), alice.getId(), Choice.FOR);
        service.vote(poll.id(), bob.getId(), Choice.AGAINST);

        assertStatus(() -> service.results(poll.id()), HttpStatus.CONFLICT);

        service.close(poll.id(), admin.getId());
        var results = service.results(poll.id());
        assertThat(results.tally()).containsEntry(Choice.FOR, 1L)
                .containsEntry(Choice.AGAINST, 1L)
                .containsEntry(Choice.ABSTAIN, 0L);
        assertThat(results.total()).isEqualTo(2);
    }

    @Test
    void cannotVoteInClosedPoll() {
        User admin = newUser(Role.ADMIN, true);
        PollView poll = service.create(admin.getId(), "Budget 2027", null);
        service.close(poll.id(), admin.getId());

        assertStatus(() -> service.vote(poll.id(), admin.getId(), Choice.FOR), HttpStatus.CONFLICT);
    }
}
