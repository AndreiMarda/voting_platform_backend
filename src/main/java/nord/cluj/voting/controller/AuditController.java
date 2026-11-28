package nord.cluj.voting.controller;

import nord.cluj.voting.dto.AuditDtos.AuditView;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Admin only (see SecurityConfig: /api/audit/**). Read-only: the trail cannot be edited through the API. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    /**
     * Newest first. Pass the last id you received as {@code beforeId} to get the next (older) page.
     * Optional filters, combined with AND: {@code from} (inclusive) and {@code to} (exclusive) as ISO instants,
     * {@code user} (part of a username or of the first / last name) and {@code action}.
     */
    @GetMapping
    public List<AuditView> list(@RequestParam(required = false) Long beforeId,
                                @RequestParam(defaultValue = "50") int limit,
                                @RequestParam(required = false) Instant from,
                                @RequestParam(required = false) Instant to,
                                @RequestParam(required = false) String user,
                                @RequestParam(required = false) AuditAction action) {
        return service.list(beforeId, limit, from, to, user, action);
    }
}
