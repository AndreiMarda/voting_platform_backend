package nord.cluj.voting.service;

import nord.cluj.voting.TestSupport;
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
    @Autowired SessionService sessionService;
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
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Budget 2027");

        service.vote(poll.id(), alice.getId(), Choice.FOR);

        assertStatus(() -> service.vote(poll.id(), alice.getId(), Choice.AGAINST), HttpStatus.CONFLICT);
    }

    @Test
    void voterSeesOwnChoiceAndNobodyElseDoes() {
        User admin = newUser(Role.ADMIN, true);
        User alice = newUser(Role.USER, true);
        User bob = newUser(Role.USER, true);
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Budget 2027");

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
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Budget 2027");

        assertStatus(() -> service.vote(poll.id(), offline.getId(), Choice.FOR), HttpStatus.FORBIDDEN);
    }

    @Test
    void everyoneSeesWhoVotedButNotWhat() {
        User admin = newUser(Role.ADMIN, true);
        User alice = newUser(Role.USER, true);
        User bob = newUser(Role.USER, true);
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Budget 2027");

        service.vote(poll.id(), alice.getId(), Choice.FOR);

        ParticipationView p = service.participation(poll.id());
        assertThat(p.voted()).containsExactly(alice.getUsername());
        // "contains", not "exactly": real users who happen to be online in the dev database are eligible too
        assertThat(p.notVoted()).contains(bob.getUsername())
                .doesNotContain(alice.getUsername(), admin.getUsername());
    }

    @Test
    void resultsAreHiddenUntilClosedThenShowTotals() {
        User admin = newUser(Role.ADMIN, true);
        User alice = newUser(Role.USER, true);
        User bob = newUser(Role.USER, true);
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Budget 2027");
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
        User alice = newUser(Role.USER, true);
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Budget 2027");
        service.close(poll.id(), admin.getId());

        assertStatus(() -> service.vote(poll.id(), alice.getId(), Choice.FOR), HttpStatus.CONFLICT);
    }

    @Test
    void theTitleStartsWithACapitalLetterWhenTheVoteIsCreated() {
        User admin = newUser(Role.ADMIN, true);

        assertThat(TestSupport.poll(sessionService, service, admin.getId(), "  budget for 2027 ").title())
                .isEqualTo("Budget for 2027");                       // trimmed and capitalised
        assertThat(TestSupport.poll(sessionService, service, admin.getId(), "ședința de mâine").title())
                .isEqualTo("Ședința de mâine");                      // diacritics
        assertThat(TestSupport.poll(sessionService, service, admin.getId(), "iPhone policy").title())
                .isEqualTo("IPhone policy");                         // only the first letter changes
        assertThat(TestSupport.poll(sessionService, service, admin.getId(), "Already Fine").title())
                .isEqualTo("Already Fine");
        assertThat(TestSupport.poll(sessionService, service, admin.getId(), "2027 budget").title())
                .isEqualTo("2027 budget");                           // not a letter: left alone
    }

    @Test
    void administratorsCannotVote() {
        User admin = newUser(Role.ADMIN, true);
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Budget 2027");

        assertStatus(() -> service.vote(poll.id(), admin.getId(), Choice.FOR), HttpStatus.FORBIDDEN);
        assertThat(service.participation(poll.id()).voted()).doesNotContain(admin.getUsername());
        assertThat(service.get(poll.id(), admin.getId()).hasVoted()).isFalse();
    }

    @Test
    void administratorsAreNotEligibleVoters() {
        User admin = newUser(Role.ADMIN, true);
        User otherAdmin = newUser(Role.ADMIN, true);
        User alice = newUser(Role.USER, true);
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Budget 2027");

        assertThat(service.get(poll.id(), admin.getId()).eligible()).isFalse();
        assertThat(service.get(poll.id(), otherAdmin.getId()).eligible()).isFalse();
        assertThat(service.get(poll.id(), alice.getId()).eligible()).isTrue();
        assertThat(service.participation(poll.id()).notVoted())
                .contains(alice.getUsername()).doesNotContain(admin.getUsername(), otherAdmin.getUsername());
    }

    @Test
    void notVotedListOnlyShowsUsersWhoAreOnlineNow() {
        User admin = newUser(Role.ADMIN, true);
        User bob = newUser(Role.USER, true);
        User carol = newUser(Role.USER, true);
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Budget 2027");

        // carol closes her browser: her heartbeat goes stale
        carol.setLastSeenAt(Instant.now().minusSeconds(60 * 10));
        users.saveAndFlush(carol);

        ParticipationView open = service.participation(poll.id());
        assertThat(open.notVoted()).contains(bob.getUsername()).doesNotContain(carol.getUsername());
        assertThat(open.notVotedOffline()).isGreaterThanOrEqualTo(1);   // carol is counted, not listed

        // once the poll is closed online status no longer matters: everyone who did not vote is listed
        service.close(poll.id(), admin.getId());
        ParticipationView closed = service.participation(poll.id());
        assertThat(closed.notVoted()).contains(bob.getUsername(), carol.getUsername());
        assertThat(closed.notVotedOffline()).isZero();
    }

    @Test
    void whoVotedStaysVisibleEvenIfTheVoterWentOffline() {
        User admin = newUser(Role.ADMIN, true);
        User alice = newUser(Role.USER, true);
        PollView poll = TestSupport.poll(sessionService, service, admin.getId(), "Budget 2027");
        service.vote(poll.id(), alice.getId(), Choice.FOR);

        alice.setLastSeenAt(null);
        users.saveAndFlush(alice);

        assertThat(service.participation(poll.id()).voted()).contains(alice.getUsername());
    }
}
