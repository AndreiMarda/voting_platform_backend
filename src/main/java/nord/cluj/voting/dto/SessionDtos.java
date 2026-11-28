package nord.cluj.voting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import nord.cluj.voting.entity.SessionStatus;

import java.time.Instant;

public final class SessionDtos {

    private SessionDtos() {
    }

    /** The name is mandatory. */
    public record StartSessionRequest(@NotBlank @Size(max = 100) String name) {
    }

    /** Same rule as starting a session: the name is mandatory. */
    public record RenameSessionRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record SessionView(Long id, String name, SessionStatus status, String startedBy,
                              Instant startedAt, Instant endedAt, long pollCount) {
    }
}
