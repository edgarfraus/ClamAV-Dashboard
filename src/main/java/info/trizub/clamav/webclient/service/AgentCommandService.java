package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AgentCommand;
import info.trizub.clamav.webclient.model.AgentCommandStatus;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.repo.AgentCommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Queue of the scans the console asks agents to run.
 *
 * Flow: the console queues a command and creates the ScanJob right away (QUEUED) →
 * the agent claims it on its poll (command DISPATCHED, job RUNNING) → the agent
 * sends the result to /api/scan/report with commandId (command DONE, job FINISHED).
 */
@Service
public class AgentCommandService {

    private static final Logger log = LoggerFactory.getLogger(AgentCommandService.class);

    /** Past this time without a result the job is not left hanging: it is closed as an error. */
    private static final Duration RESULT_TIMEOUT = Duration.ofHours(6);
    /** A command still unclaimed after this long means an agent that is off or not installed. */
    private static final Duration PICKUP_TIMEOUT = Duration.ofHours(24);

    private final AgentCommandRepository repo;
    private final ScanJobService jobs;

    public AgentCommandService(AgentCommandRepository repo, ScanJobService jobs) {
        this.repo = repo;
        this.jobs = jobs;
    }

    /** Queues a scan for the agent and creates the matching job. */
    @Transactional
    public AgentCommand enqueue(ClamdEndpoint endpoint, String target, String username) {
        ScanJob job = jobs.createAgentJob(target, endpoint, username);

        AgentCommand cmd = new AgentCommand();
        cmd.setEndpoint(endpoint);
        cmd.setTarget(target);
        cmd.setStatus(AgentCommandStatus.PENDING);
        cmd.setJobId(job.getId());
        cmd.setCreatedBy(username);
        cmd.setCreatedAt(Instant.now());
        return repo.save(cmd);
    }

    /**
     * Hands the pending commands to the agent and marks them claimed. Their jobs
     * move to RUNNING: from here on the scan is in progress on the machine.
     */
    @Transactional
    public List<AgentCommand> claimPending(ClamdEndpoint endpoint) {
        List<AgentCommand> pending =
                repo.findByEndpointAndStatusOrderByCreatedAtAsc(endpoint, AgentCommandStatus.PENDING);
        Instant now = Instant.now();
        for (AgentCommand cmd : pending) {
            cmd.setStatus(AgentCommandStatus.DISPATCHED);
            cmd.setDispatchedAt(now);
            repo.save(cmd);
            if (cmd.getJobId() != null) {
                try {
                    jobs.markRunning(cmd.getJobId());
                } catch (Exception e) {
                    log.warn("Could not mark job {} RUNNING: {}", cmd.getJobId(), e.getMessage());
                }
            }
        }
        return pending;
    }

    /**
     * A command claimed by an agent, looked up by id and checked against the
     * endpoint reporting it: an agent cannot close another host's commands.
     */
    public Optional<AgentCommand> findForEndpoint(Long commandId, ClamdEndpoint endpoint) {
        if (commandId == null || endpoint == null) return Optional.empty();
        return repo.findById(commandId)
                .filter(c -> c.getEndpoint() != null && c.getEndpoint().getId().equals(endpoint.getId()));
    }

    @Transactional
    public void markDone(Long commandId) {
        repo.findById(commandId).ifPresent(cmd -> {
            cmd.setStatus(AgentCommandStatus.DONE);
            cmd.setCompletedAt(Instant.now());
            repo.save(cmd);
        });
    }

    public List<AgentCommand> pendingFor(ClamdEndpoint endpoint) {
        return repo.findByEndpointAndStatusOrderByCreatedAtAsc(endpoint, AgentCommandStatus.PENDING);
    }

    /**
     * Closes the commands left unanswered. Without this, an agent that is off
     * would leave jobs hanging in RUNNING forever — exactly the problem that
     * makes a job list untrustworthy.
     */
    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void expireStale() {
        Instant now = Instant.now();

        for (AgentCommand cmd : repo.findByStatusAndDispatchedAtBefore(
                AgentCommandStatus.DISPATCHED, now.minus(RESULT_TIMEOUT))) {
            failCommand(cmd, "The agent claimed the scan but did not send a result within "
                    + RESULT_TIMEOUT.toHours() + " hours.");
        }

        for (AgentCommand cmd : repo.findByStatusAndCreatedAtBefore(
                AgentCommandStatus.PENDING, now.minus(PICKUP_TIMEOUT))) {
            failCommand(cmd, "No agent claimed the scan within "
                    + PICKUP_TIMEOUT.toHours() + " hours: agent off or not installed on "
                    + (cmd.getEndpoint() != null ? cmd.getEndpoint().getName() : "this endpoint") + ".");
        }
    }

    private void failCommand(AgentCommand cmd, String reason) {
        if (cmd.getJobId() != null) {
            try {
                jobs.finishError(cmd.getJobId(), reason);
            } catch (Exception e) {
                log.warn("Could not close job {} as an error: {}", cmd.getJobId(), e.getMessage());
            }
        }
        cmd.setStatus(AgentCommandStatus.DONE);
        cmd.setCompletedAt(Instant.now());
        repo.save(cmd);
        log.info("Agent command {} expired: {}", cmd.getId(), reason);
    }
}
