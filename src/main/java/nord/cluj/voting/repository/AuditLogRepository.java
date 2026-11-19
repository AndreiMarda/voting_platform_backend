package nord.cluj.voting.repository;

import nord.cluj.voting.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findAllByOrderByIdDesc(Pageable page);

    /** Next page when scrolling back in time: entries older than the given id. */
    List<AuditLog> findByIdLessThanOrderByIdDesc(Long id, Pageable page);
}
