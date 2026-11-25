package nord.cluj.voting.repository;

import nord.cluj.voting.entity.DeletedPoll;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeletedPollRepository extends JpaRepository<DeletedPoll, Long> {

    List<DeletedPoll> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
