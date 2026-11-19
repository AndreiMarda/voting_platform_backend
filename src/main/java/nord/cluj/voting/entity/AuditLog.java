package nord.cluj.voting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One line of the audit trail. Append-only: nothing here is ever updated. */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_name", length = 100, updatable = false)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private AuditAction action;

    @Column(name = "target_type", length = 30, updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(columnDefinition = "text", updatable = false)
    private String details;

    @Column(length = 45, updatable = false)
    private String ip;

    protected AuditLog() {
    }

    public AuditLog(Long actorId, String actorName, AuditAction action, String targetType, Long targetId,
                    String details, String ip) {
        this.actorId = actorId;
        this.actorName = actorName;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details;
        this.ip = ip;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public Long getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public AuditAction getAction() { return action; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public String getDetails() { return details; }
    public String getIp() { return ip; }
}
