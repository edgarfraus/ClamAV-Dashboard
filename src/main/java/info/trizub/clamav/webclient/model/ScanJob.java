package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scan_jobs")
public class ScanJob {

    @Id
    @Column(length = 64)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScanJobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScanJobStatus status = ScanJobStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, columnDefinition = "varchar(20)")
    private ScanVerdict verdict;

    @Column(nullable = false, length = 2048)
    private String target;

    @Column(length = 1024)
    private String storedPath; // for uploaded file on disk (if any)

    @Column(length = 128)
    private String sha256;

    private Long sizeBytes;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "endpoint_id")
    private ClamdEndpoint endpoint;

    @Column(length = 64)
    private String submittedBy;

    // Hostname the scan actually ran on, for EXTERNAL jobs reported via /api/scan/report
    // (the machine running clamdscan is not necessarily a registered ClamdEndpoint).
    @Column(length = 255)
    private String sourceHost;

    private Instant submittedAt = Instant.now();
    private Instant startedAt;
    private Instant finishedAt;

    // IMPORTANT (PostgreSQL): do NOT use @Lob for large Strings here.
    // Mapping as CLOB can route to OID Large Objects and fail with:
    //   "Large Objects may not be used in auto-commit mode"
    @Column(columnDefinition = "TEXT")
    private String foundVirusesJson; // Map<file, viruses> serialized

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(length = 2048)
    private String quarantinePath;

    // What actually happened to the infected file: null for jobs that are not
    // VIRUS_FOUND (or reported by agents too old to send it). The descriptive
    // path (where it was quarantined, on the console or on the remote machine
    // that reports it) stays in quarantinePath, shared by both cases.
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RemediationStatus remediationStatus;

    @Column(columnDefinition = "boolean default false")
    private boolean acknowledged = false;

    @Column(length = 64)
    private String acknowledgedBy;

    private Instant acknowledgedAt;

    public ScanJob() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public ScanJobType getType() { return type; }
    public void setType(ScanJobType type) { this.type = type; }
    public ScanJobStatus getStatus() { return status; }
    public void setStatus(ScanJobStatus status) { this.status = status; }
    public ScanVerdict getVerdict() { return verdict; }
    public void setVerdict(ScanVerdict verdict) { this.verdict = verdict; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public ClamdEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(ClamdEndpoint endpoint) { this.endpoint = endpoint; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }
    public String getSourceHost() { return sourceHost; }
    public void setSourceHost(String sourceHost) { this.sourceHost = sourceHost; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getFoundVirusesJson() { return foundVirusesJson; }
    public void setFoundVirusesJson(String foundVirusesJson) { this.foundVirusesJson = foundVirusesJson; }

    // --- Parsed view of foundVirusesJson (infected file path -> list of signatures) ---
    // Transient + JsonIgnore: not persisted, not serialized in the REST API.
    // Lets templates render which files are infected without dumping raw JSON.
    private static final com.fasterxml.jackson.databind.ObjectMapper FOUND_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Transient
    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.util.Map<String, java.util.List<String>> getFoundVirusesMap() {
        if (foundVirusesJson == null || foundVirusesJson.isBlank()) return java.util.Collections.emptyMap();
        try {
            return FOUND_MAPPER.readValue(foundVirusesJson,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, java.util.List<String>>>() {});
        } catch (Exception e) {
            // Malformed JSON: surface it rather than hiding the detection.
            return java.util.Collections.singletonMap(foundVirusesJson, java.util.Collections.emptyList());
        }
    }

    @Transient
    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.util.Set<String> getDistinctSignatures() {
        java.util.LinkedHashSet<String> sigs = new java.util.LinkedHashSet<>();
        getFoundVirusesMap().values().forEach(sigs::addAll);
        return sigs;
    }

    @Transient
    @com.fasterxml.jackson.annotation.JsonIgnore
    public int getInfectedFileCount() { return getFoundVirusesMap().size(); }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getQuarantinePath() { return quarantinePath; }
    public void setQuarantinePath(String quarantinePath) { this.quarantinePath = quarantinePath; }
    public RemediationStatus getRemediationStatus() { return remediationStatus; }
    public void setRemediationStatus(RemediationStatus remediationStatus) { this.remediationStatus = remediationStatus; }
    public boolean isAcknowledged() { return acknowledged; }
    public void setAcknowledged(boolean acknowledged) { this.acknowledged = acknowledged; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
}
