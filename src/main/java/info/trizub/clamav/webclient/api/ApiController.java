package info.trizub.clamav.webclient.api;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.service.ClamavClientProvider;
import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.service.ScanJobService;
import info.trizub.clamav.webclient.util.ClamVersionInfo;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    public ApiController(EndpointService endpoints, ScanJobService jobs,
                         ClamavClientProvider clientProvider) {
        this.endpoints = endpoints;
        this.jobs = jobs;
        this.clientProvider = clientProvider;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "endpoints", endpoints.all().stream().map(ClamdEndpoint::getName).toList()
        );
    }

    @GetMapping("/endpoints/{id}/status")
    public Map<String, Object> endpointStatus(@PathVariable Long id) {
        ClamdEndpoint ep = endpoints.get(id);
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
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return Map.of("online", false, "error", msg != null ? msg : "timeout");
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
        var job = jobs.createPathJob(req.path, ep, auth.getName());
        return Map.of("jobId", job.getId());
    }
}
