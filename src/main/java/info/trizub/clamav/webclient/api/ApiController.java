package info.trizub.clamav.webclient.api;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.RemediationStatus;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.model.AgentCommand;
import info.trizub.clamav.webclient.service.AgentCommandService;
import info.trizub.clamav.webclient.service.ClamavClientProvider;
import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.service.ScanJobService;
import info.trizub.clamav.webclient.config.AgentAuthenticationFilter;
import info.trizub.clamav.webclient.util.ClamVersionInfo;
import info.trizub.clamav.webclient.util.ConnectionDiagnosis;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final EndpointService endpoints;
    private final ScanJobService jobs;
    private final ClamavClientProvider clientProvider;
    private final AgentCommandService agentCommands;

    public ApiController(EndpointService endpoints, ScanJobService jobs,
                         ClamavClientProvider clientProvider,
                         AgentCommandService agentCommands) {
        this.endpoints = endpoints;
        this.jobs = jobs;
        this.clientProvider = clientProvider;
        this.agentCommands = agentCommands;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "endpoints", endpoints.all().stream().map(ClamdEndpoint::getName).toList()
        );
    }

    /** Un agent visto entro questa finestra e' considerato vivo (il poll Windows e' ogni 5 min). */
    private static final Duration AGENT_ALIVE_WINDOW = Duration.ofMinutes(15);

    @GetMapping("/endpoints/{id}/status")
    public Map<String, Object> endpointStatus(@PathVariable Long id) {
        ClamdEndpoint ep = endpoints.get(id);

        // Endpoint con agent: la console non lo contatta piu'. Lo stato lo
        // deduciamo da quando l'agent si e' fatto vivo l'ultima volta, e la
        // versione delle firme da quella che ci ha riportato.
        if (ep.isAgentEnrolled()) {
            return agentStatus(ep);
        }

        // Senza agent e senza host non c'e' proprio niente da contattare.
        if (ep.getHost() == null || ep.getHost().isBlank()) {
            return Map.of("online", false,
                    "error", "Nessun host configurato e nessun agent installato su questo endpoint");
        }

        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                var client = clientProvider.clientFor(ep);
                client.ping();
                return client.version();
            });
            String versionStr = future.get(5, TimeUnit.SECONDS);
            ClamVersionInfo info = ClamVersionInfo.parse(versionStr);
            return Map.of(
                    "online", true,
                    "clamVersion", info.clamVersion != null ? info.clamVersion : "",
                    "dbVersion", info.dbVersion != null ? info.dbVersion : "",
                    "dbDate", info.dbDate != null ? info.dbDate : "",
                    "dbAgeDays", info.dbAgeDays,
                    "stale", info.stale
            );
        } catch (Exception e) {
            return Map.of("online", false,
                    "error", ConnectionDiagnosis.explain(ep.getHost(), ep.getPort(), e));
        }
    }

    @GetMapping("/jobs")
    public List<ScanJob> listJobs() { return jobs.latest(); }

    @GetMapping("/jobs/active")
    public List<Map<String, Object>> activeJobs() {
        return jobs.activeJobs().stream().map(j -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String id = j.getId();
            m.put("id", id);
            m.put("idShort", id != null && id.length() >= 8 ? id.substring(0, 8) : id);
            m.put("type", j.getType() != null ? j.getType().name() : null);
            m.put("status", j.getStatus() != null ? j.getStatus().name() : null);
            m.put("target", j.getTarget());
            m.put("endpointName", j.getEndpoint() != null ? j.getEndpoint().getName() : null);
            m.put("submittedAt", j.getSubmittedAt() != null ? j.getSubmittedAt().toString() : null);
            return m;
        }).toList();
    }

    @GetMapping("/jobs/{id}")
    public ScanJob getJob(@PathVariable String id) {
        ScanJob job = jobs.getOrNull(id);
        if (job == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "job not found");
        }
        return job;
    }

    private Map<String, Object> agentStatus(ClamdEndpoint ep) {
        Map<String, Object> out = new LinkedHashMap<>();
        Instant seen = ep.getAgentLastSeenAt();
        boolean alive = seen != null && seen.isAfter(Instant.now().minus(AGENT_ALIVE_WINDOW));

        out.put("online", alive);
        out.put("agent", true);
        out.put("lastSeen", seen != null ? seen.toString() : "");
        if (!alive) {
            out.put("error", seen == null
                    ? "Agent mai visto: installa l'agent su questa macchina"
                    : "Agent non risponde da " + seen);
        }

        ClamVersionInfo info = ClamVersionInfo.parse(ep.getAgentClamdVersion());
        out.put("clamVersion", info.clamVersion != null ? info.clamVersion : "");
        out.put("dbVersion", info.dbVersion != null ? info.dbVersion : "");
        out.put("dbDate", info.dbDate != null ? info.dbDate : "");
        out.put("dbAgeDays", info.dbAgeDays);
        out.put("stale", info.stale);
        return out;
    }

    @PostMapping(value = "/scan/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> scanUpload(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam("endpointId") Long endpointId,
                                         Authentication auth) {
        var ep = endpoints.get(endpointId);
        var created = jobs.createUploadJobs(files, ep, auth.getName());
        return Map.of(
                "created", created.size(),
                "jobIds", created.stream().map(ScanJob::getId).toList()
        );
    }

    public static class PathScanRequest {
        @NotBlank public String path;
        public Long endpointId;
    }

    @PostMapping(value = "/scan/path", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> scanPath(@RequestBody PathScanRequest req, Authentication auth) {
        var ep = req.endpointId != null ? endpoints.get(req.endpointId) : endpoints.defaultEndpoint();
        // Stessa scelta di WebUiController.scanPath: un endpoint con agent non ha
        // host da contattare via TCP, la scansione la deve fare l'agent.
        if (ep.isAgentEnrolled()) {
            var cmd = agentCommands.enqueue(ep, req.path, auth.getName());
            return Map.of("jobId", cmd.getJobId());
        }
        var job = jobs.createPathJob(req.path, ep, auth.getName());
        return Map.of("jobId", job.getId());
    }

    public static class ScanReportRequest {
        @NotBlank public String hostname;
        public String path;
        @NotBlank public String verdict; // VIRUS_FOUND | ERROR
        public List<String> findings; // raw "path: SIGNATURE FOUND" lines, VIRUS_FOUND only
        public String errorMessage; // ERROR only
        public String source; // "realtime" for clamonacc on-access events; anything else = batch scan
        public Long commandId; // set when this is the result of a scan the console asked for
        // What the reporting side did to the infected file, VIRUS_FOUND only:
        // "quarantined" | "removed" | "failed" | absent/anything else = not_attempted.
        // The console has no channel of its own into the machine, so this is the
        // only way it ever learns what really happened to the file.
        public String remediation;
        public String remediationPath; // where it ended up, only for "quarantined"
    }

    private static RemediationStatus parseRemediation(String raw) {
        if (raw == null) return RemediationStatus.NOT_ATTEMPTED;
        switch (raw.trim().toLowerCase()) {
            case "quarantined": return RemediationStatus.QUARANTINED;
            case "removed": return RemediationStatus.REMOVED;
            case "failed": return RemediationStatus.FAILED;
            default: return RemediationStatus.NOT_ATTEMPTED;
        }
    }

    /**
     * Ingests the result of a scan executed outside this app (e.g. a clamdscan cron job on a
     * fleet machine talking to its local clamd directly). Only VIRUS_FOUND/ERROR are accepted —
     * this endpoint exists to surface alerts in the dashboard/Telegram, not to log every clean run.
     *
     * With a {@code commandId} the report closes a scan this console asked the agent to run:
     * that job already exists (created QUEUED at dispatch), so it is finished rather than
     * duplicated, and OK is accepted too — a clean result is what the user is waiting for.
     */
    @PostMapping(value = "/scan/report", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String,Object>> scanReport(@RequestBody ScanReportRequest req, Authentication auth,
                                                        HttpServletRequest httpRequest) {
        ScanVerdict verdict;
        try {
            verdict = ScanVerdict.valueOf(req.verdict.trim().toUpperCase());
        } catch (Exception e) {
            return badRequest("verdict must be OK, VIRUS_FOUND or ERROR");
        }
        // OK e' accettato solo per una scansione che la console ha chiesto: li'
        // "nessun virus" e' l'esito che l'utente sta aspettando. Per i report
        // spontanei (cron, on-access) resta escluso, altrimenti ogni run pulito
        // riempirebbe la lista dei job.
        boolean commandResult = req.commandId != null;
        if (verdict != ScanVerdict.VIRUS_FOUND && verdict != ScanVerdict.ERROR
                && !(commandResult && verdict == ScanVerdict.OK)) {
            return badRequest("verdict must be VIRUS_FOUND or ERROR"
                    + " (OK is accepted only together with commandId)");
        }

        Map<String, List<String>> found = new LinkedHashMap<>();
        if (req.findings != null) {
            for (String line : req.findings) {
                if (line == null || line.isBlank()) continue;
                int idx = line.indexOf(": ");
                String path = idx >= 0 ? line.substring(0, idx) : line;
                String sig = idx >= 0 ? line.substring(idx + 2) : "";
                sig = sig.replaceAll("(?i)\\s+FOUND$", "").trim();
                found.computeIfAbsent(path, k -> new ArrayList<>()).add(sig);
            }
        }

        ScanJobType type = req.source != null && "realtime".equalsIgnoreCase(req.source.trim())
                ? ScanJobType.REALTIME : ScanJobType.EXTERNAL;

        // Report inviato da un agent: lo leghiamo all'endpoint della sua chiave.
        // Con l'autenticazione OPERATOR classica l'attributo non c'e' e resta null.
        ClamdEndpoint reportingEndpoint = null;
        Object endpointId = httpRequest.getAttribute(AgentAuthenticationFilter.ENDPOINT_ID_ATTRIBUTE);
        if (endpointId instanceof Long) {
            try {
                reportingEndpoint = endpoints.get((Long) endpointId);
            } catch (Exception ignored) {
            }
        }

        // Esito di una scansione chiesta dalla console: il job esiste gia' (creato
        // QUEUED quando l'hai lanciata), quindi lo chiudiamo invece di crearne uno nuovo.
        if (commandResult) {
            AgentCommand cmd = agentCommands.findForEndpoint(req.commandId, reportingEndpoint).orElse(null);
            if (cmd == null) {
                return badRequest("commandId sconosciuto per questo agent: " + req.commandId);
            }
            String jobId = cmd.getJobId();
            if (jobId != null) {
                if (verdict == ScanVerdict.VIRUS_FOUND) {
                    jobs.finishFound(jobId, found, parseRemediation(req.remediation), req.remediationPath);
                } else if (verdict == ScanVerdict.ERROR) {
                    jobs.finishError(jobId, req.errorMessage);
                } else {
                    jobs.finishOk(jobId);
                }
                jobs.notifyIfNeeded(jobId);
            }
            agentCommands.markDone(cmd.getId());
            return ResponseEntity.ok(Map.of("jobId", jobId == null ? "" : jobId));
        }

        var job = jobs.createExternalReport(req.hostname, req.path, verdict, found, req.errorMessage,
                type, reportingEndpoint, auth.getName(), parseRemediation(req.remediation), req.remediationPath);
        return ResponseEntity.ok(Map.of("jobId", job.getId()));
    }

    /**
     * Errore di validazione con un messaggio leggibile. Senza questo l'eccezione
     * risalirebbe non gestita (GlobalExceptionHandler copre solo il package web)
     * e l'agent riceverebbe un 500 opaco al posto del motivo del rifiuto.
     */
    private ResponseEntity<Map<String,Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
