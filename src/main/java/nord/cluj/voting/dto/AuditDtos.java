package nord.cluj.voting.dto;

import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.entity.AuditLog;

import java.time.Instant;

public final class AuditDtos {

    private AuditDtos() {
    }

    public record AuditView(Long id, Instant occurredAt, String actorName, AuditAction action,
                            String targetType, Long targetId, String details, String ip) {
        public static AuditView of(AuditLog a) {
            return new AuditView(a.getId(), a.getOccurredAt(), a.getActorName(), a.getAction(),
                    a.getTargetType(), a.getTargetId(), a.getDetails(), a.getIp());
        }
    }
}
