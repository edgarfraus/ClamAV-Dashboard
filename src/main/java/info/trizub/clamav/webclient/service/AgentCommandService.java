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
 * Coda delle scansioni che la console chiede agli agent.
 *
 * Flusso: la console accoda un comando e crea subito il ScanJob (QUEUED) →
 * l'agent lo ritira al poll (comando DISPATCHED, job RUNNING) → l'agent invia
 * l'esito su /api/scan/report con commandId (comando DONE, job FINISHED).
 */
@Service
public class AgentCommandService {

    private static final Logger log = LoggerFactory.getLogger(AgentCommandService.class);

    /** Oltre questo tempo senza esito, il job non resta appeso: viene chiuso in errore. */
    private static final Duration RESULT_TIMEOUT = Duration.ofHours(6);
    /** Un comando mai ritirato oltre questo tempo indica un agent spento o non installato. */
    private static final Duration PICKUP_TIMEOUT = Duration.ofHours(24);

    private final AgentCommandRepository repo;
    private final ScanJobService jobs;

    public AgentCommandService(AgentCommandRepository repo, ScanJobService jobs) {
        this.repo = repo;
        this.jobs = jobs;
    }

    /** Accoda una scansione per l'agent e crea il job corrispondente. */
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
     * Consegna all'agent i comandi in attesa e li marca come ritirati. I job
     * passano a RUNNING: da qui in poi la scansione e' in corso sulla macchina.
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
                    log.warn("Impossibile marcare RUNNING il job {}: {}", cmd.getJobId(), e.getMessage());
                }
            }
        }
        return pending;
    }

    /**
     * Comando ritirato dall'agent, cercato per id e verificato contro l'endpoint
     * che lo sta riportando: un agent non puo' chiudere i comandi di un altro host.
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
     * Chiude i comandi rimasti senza risposta. Senza questo un agent spento
     * lascerebbe job appesi in RUNNING per sempre — esattamente il problema che
     * rende la lista dei job poco affidabile.
     */
    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void expireStale() {
        Instant now = Instant.now();

        for (AgentCommand cmd : repo.findByStatusAndDispatchedAtBefore(
                AgentCommandStatus.DISPATCHED, now.minus(RESULT_TIMEOUT))) {
            failCommand(cmd, "L'agent ha ritirato la scansione ma non ha inviato l'esito entro "
                    + RESULT_TIMEOUT.toHours() + " ore.");
        }

        for (AgentCommand cmd : repo.findByStatusAndCreatedAtBefore(
                AgentCommandStatus.PENDING, now.minus(PICKUP_TIMEOUT))) {
            failCommand(cmd, "Nessun agent ha ritirato la scansione entro "
                    + PICKUP_TIMEOUT.toHours() + " ore: agent spento o non installato su "
                    + (cmd.getEndpoint() != null ? cmd.getEndpoint().getName() : "questo endpoint") + ".");
        }
    }

    private void failCommand(AgentCommand cmd, String reason) {
        if (cmd.getJobId() != null) {
            try {
                jobs.finishError(cmd.getJobId(), reason);
            } catch (Exception e) {
                log.warn("Impossibile chiudere in errore il job {}: {}", cmd.getJobId(), e.getMessage());
            }
        }
        cmd.setStatus(AgentCommandStatus.DONE);
        cmd.setCompletedAt(Instant.now());
        repo.save(cmd);
        log.info("Comando agent {} scaduto: {}", cmd.getId(), reason);
    }
}
