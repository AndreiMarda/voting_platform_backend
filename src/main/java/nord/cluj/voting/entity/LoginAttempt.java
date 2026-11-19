package nord.cluj.voting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A failed login, remembered briefly so repeated guessing can be blocked. */
@Entity
@Table(name = "login_attempts")
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username_key", nullable = false, length = 100)
    private String usernameKey;

    @Column(nullable = false, length = 45)
    private String ip;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    protected LoginAttempt() {
    }

    public LoginAttempt(String usernameKey, String ip, Instant attemptedAt) {
        this.usernameKey = usernameKey;
        this.ip = ip;
        this.attemptedAt = attemptedAt;
    }

    public Long getId() { return id; }
    public String getUsernameKey() { return usernameKey; }
    public String getIp() { return ip; }
    public Instant getAttemptedAt() { return attemptedAt; }
}
