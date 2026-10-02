package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.AgentCommand;
import info.trizub.clamav.webclient.model.AgentCommandStatus;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AgentCommandRepository extends JpaRepository<AgentCommand, Long> {
    List<AgentCommand> findByEndpointAndStatusOrderByCreatedAtAsc(ClamdEndpoint endpoint, AgentCommandStatus status);
    List<AgentCommand> findByStatusAndDispatchedAtBefore(AgentCommandStatus status, Instant before);
    List<AgentCommand> findByStatusAndCreatedAtBefore(AgentCommandStatus status, Instant before);
    // A list, not an Optional: an alert can carry several file actions.
    List<AgentCommand> findByJobIdOrderByCreatedAtDesc(String jobId);
    List<AgentCommand> findByEndpoint(ClamdEndpoint endpoint);
}
