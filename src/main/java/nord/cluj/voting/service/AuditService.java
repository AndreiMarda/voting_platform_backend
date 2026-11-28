package nord.cluj.voting.service;

import nord.cluj.voting.dto.AuditDtos.AuditView;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.entity.AuditLog;
import nord.cluj.voting.repository.AuditLogRepository;
import nord.cluj.voting.security.AuthUser;
import jakarta.persistence.criteria.Predicate;
import nord.cluj.voting.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The audit trail. Entries are written in the same transaction as the action they describe, so a rolled-back
 * action leaves no entry. Never log who voted for what here: that would defeat anonymous votes.
 */
@Service
public class AuditService {

    private static final int MAX_DETAILS = 500;
    private static final int MAX_PAGE = 200;

    /** Still recorded, but not shown in the audit log (page, search and PDF export). */
    static final java.util.Set<AuditAction> HIDDEN = java.util.Set.of(AuditAction.USER_ACTIVATED, AuditAction.USER_DEACTIVATED);

    private final AuditLogRepository entries;

    private final UserRepository users;

    public AuditService(AuditLogRepository entries, UserRepository users) {
        this.entries = entries;
        this.users = users;
    }

    /** Records an action by whoever is logged in on the current request. */
    @Transactional
    public void record(AuditAction action, String targetType, Long targetId, String details) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            recordAs(user.getId(), user.getUsername(), action, targetType, targetId, details);
        } else {
            recordAs(null, null, action, targetType, targetId, details);
        }
    }

    /** Records an action with an explicit actor (login and logout, when nobody is "logged in" yet / any more). */
    @Transactional
    public void recordAs(Long actorId, String actorName, AuditAction action, String targetType, Long targetId,
                         String details) {
        entries.save(new AuditLog(actorId, cut(actorName, 100), action, targetType, targetId,
                cut(details, MAX_DETAILS), currentIp()));
    }

    /** Newest first. {@code beforeId} continues after the last entry of the previous page. */
    @Transactional(readOnly = true)
    public List<AuditView> list(Long beforeId, int limit) {
        return list(beforeId, limit, null, null, null, null);
    }

    /**
     * Newest first, with optional filters (all combined with AND): {@code from} (inclusive) and {@code to} (exclusive)
     * on the time of the entry, {@code user} (part of the username or of the person's first / last name, any case)
     * and {@code action}.
     */
    @Transactional(readOnly = true)
    public List<AuditView> list(Long beforeId, int limit, Instant from, Instant to, String user, AuditAction action) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, MAX_PAGE)), Sort.by(Sort.Direction.DESC, "id"));
        List<Long> userIds = List.of();
        String pattern = null;
        if (user != null && !user.isBlank()) {
            pattern = "%" + escapeLike(user.trim().toLowerCase(Locale.ROOT)) + "%";
            userIds = users.findIdsByNameLike(pattern);
        }
        final String namePattern = pattern;
        final List<Long> ids = userIds;
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (beforeId != null) all.add(cb.lessThan(root.<Long>get("id"), beforeId));
            if (from != null) all.add(cb.greaterThanOrEqualTo(root.<Instant>get("occurredAt"), from));
            if (to != null) all.add(cb.lessThan(root.<Instant>get("occurredAt"), to));
            all.add(cb.not(root.get("action").in(HIDDEN)));
            if (action != null) all.add(cb.equal(root.get("action"), action));
            if (namePattern != null) {
                Predicate byName = cb.like(cb.lower(root.<String>get("actorName")), namePattern, '!');
                all.add(ids.isEmpty() ? byName : cb.or(byName, root.<Long>get("actorId").in(ids)));
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
        return entries.findAll(spec, page).getContent().stream().map(AuditView::of).toList();
    }

    /** Makes user input literal inside a LIKE pattern (escape character is '!'). */
    static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String currentIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest().getRemoteAddr() : null;
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
