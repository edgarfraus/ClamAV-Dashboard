package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.AgentCommand;
import info.trizub.clamav.webclient.model.AgentCommandStatus;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentCommandRepository extends JpaRepository<AgentCommand, Long> {
    List<AgentCommand> findByEndpointAndStatusOrderByCreatedAtAsc(ClamdEndpoint endpoint, AgentCommandStatus status);
    List<AgentCommand> findByStatusAndDispatchedAtBefore(AgentCommandStatus status, Instant before);
    List<AgentCommand> findByStatusAndCreatedAtBefore(AgentCommandStatus status, Instant before);
    Optional<AgentCommand> findByJobId(String jobId);
}
