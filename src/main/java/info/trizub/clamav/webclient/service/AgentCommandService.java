package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AgentCommand;
import info.trizub.clamav.webclient.model.AgentCommandStatus;
import info.trizub.clamav.webclient.model.AgentCommandType;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.repo.AgentCommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private final ApplicationEventPublisher events;

    public AgentCommandService(AgentCommandRepository repo, ScanJobService jobs, ApplicationEventPublisher events) {
        this.repo = repo;
        this.jobs = jobs;
        this.events = events;
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
     * Queues an action on one file of an alert (quarantine, restore). No job is
     * created: the alert's own job is the one the result updates.
     */
    @Transactional
    public AgentCommand enqueueFileAction(ClamdEndpoint endpoint, String jobId, AgentCommandType type,
                                          String target, String username) {
        AgentCommand cmd = new AgentCommand();
        cmd.setEndpoint(endpoint);
        cmd.setType(type);
        cmd.setTarget(target);
        cmd.setStatus(AgentCommandStatus.PENDING);
        cmd.setJobId(jobId);
        cmd.setCreatedBy(username);
        cmd.setCreatedAt(Instant.now());
        return repo.save(cmd);
    }

    /**
     * Hands the pending commands to the agent and marks them claimed. Scan jobs
     * move to RUNNING: from here on the scan is in progress on the machine.
     *
     * File actions go only to an agent that advertises them. An older agent
     * reads every command as a scan, so it would "scan" the file instead of
     * moving it and report a result for the wrong thing; such a command stays
     * pending and expires with a reason instead.
     */
    @Transactional
    public List<AgentCommand> claimPending(ClamdEndpoint endpoint) {
        List<AgentCommand> pending =
                repo.findByEndpointAndStatusOrderByCreatedAtAsc(endpoint, AgentCommandStatus.PENDING);
        boolean fileActions = endpoint.isFileActionsSupported();
        List<AgentCommand> claimed = new ArrayList<>();
        Instant now = Instant.now();
        for (AgentCommand cmd : pending) {
            if (cmd.getType().isFileAction() && !fileActions) continue;
            cmd.setStatus(AgentCommandStatus.DISPATCHED);
            cmd.setDispatchedAt(now);
            repo.save(cmd);
            claimed.add(cmd);
            if (cmd.getJobId() != null && cmd.getType() == AgentCommandType.SCAN) {
                try {
                    jobs.markRunning(cmd.getJobId());
                } catch (Exception e) {
                    log.warn("Could not mark job {} RUNNING: {}", cmd.getJobId(), e.getMessage());
                }
            }
        }
        return claimed;
    }

    /**
     * The agent's answer to a file action. On success the alert's job learns
     * where the file is now, which is what the next button on the page needs.
     */
    @Transactional
    public void recordFileActionResult(AgentCommand cmd, boolean ok, String message, String quarantinePath) {
        cmd.setStatus(AgentCommandStatus.DONE);
        cmd.setCompletedAt(Instant.now());
        cmd.setSucceeded(ok);
        cmd.setResultMessage(message);
        repo.save(cmd);
        if (ok && cmd.getJobId() != null) {
            jobs.applyFileAction(cmd.getJobId(), cmd.getType(), cmd.getFilePath(), quarantinePath);
        }
        log.info("File action {} {} on {} ({}): {}", cmd.getId(), cmd.getType(), cmd.getFilePath(),
                ok ? "done" : "FAILED", message);
        events.publishEvent(new FileActionCompletedEvent(cmd, ok, message, quarantinePath));
    }

    /** File actions on one alert, newest first. */
    public List<AgentCommand> fileActionsForJob(String jobId) {
        if (jobId == null) return List.of();
        return repo.findByJobIdOrderByCreatedAtDesc(jobId).stream()
                .filter(c -> c.getType().isFileAction())
                .toList();
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
            failCommand(cmd, "The agent claimed the " + noun(cmd) + " but did not send a result within "
                    + RESULT_TIMEOUT.toHours() + " hours.");
        }

        for (AgentCommand cmd : repo.findByStatusAndCreatedAtBefore(
                AgentCommandStatus.PENDING, now.minus(PICKUP_TIMEOUT))) {
            String endpointName = cmd.getEndpoint() != null ? cmd.getEndpoint().getName() : "this endpoint";
            failCommand(cmd, "No agent claimed the " + noun(cmd) + " within "
                    + PICKUP_TIMEOUT.toHours() + " hours: agent off or not installed on " + endpointName
                    + (cmd.getType().isFileAction() ? ", or too old to run file actions (reinstall it)." : "."));
        }
    }

    private static String noun(AgentCommand cmd) {
        return cmd.getType().isFileAction() ? "file action" : "scan";
    }

    private void failCommand(AgentCommand cmd, String reason) {
        // A file action belongs to an alert whose job is long finished: failing
        // that job would overwrite the detection with an unrelated error.
        if (cmd.getType().isFileAction()) {
            cmd.setSucceeded(false);
            cmd.setResultMessage(reason);
            events.publishEvent(new FileActionCompletedEvent(cmd, false, reason, null));
        } else if (cmd.getJobId() != null) {
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
