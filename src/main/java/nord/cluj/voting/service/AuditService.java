package nord.cluj.voting.service;

import nord.cluj.voting.dto.AuditDtos.AuditView;
import nord.cluj.voting.entity.AuditAction;
import nord.cluj.voting.entity.AuditLog;
import nord.cluj.voting.repository.AuditLogRepository;
import nord.cluj.voting.security.AuthUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * The audit trail. Entries are written in the same transaction as the action they describe, so a rolled-back
 * action leaves no entry. Never log who voted for what here: that would defeat anonymous votes.
 */
@Service
public class AuditService {

    private static final int MAX_DETAILS = 500;
    private static final int MAX_PAGE = 200;

    private final AuditLogRepository entries;

    public AuditService(AuditLogRepository entries) {
        this.entries = entries;
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
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, MAX_PAGE)));
        List<AuditLog> found = beforeId == null
                ? entries.findAllByOrderByIdDesc(page)
                : entries.findByIdLessThanOrderByIdDesc(beforeId, page);
        return found.stream().map(AuditView::of).toList();
    }

    private static String currentIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest().getRemoteAddr() : null;
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
