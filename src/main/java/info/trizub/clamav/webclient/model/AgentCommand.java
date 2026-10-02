package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A scan the console has asked the agent to run on its own machine. It exists
 * because PATH scans over TCP fail when clamd cannot read the path
 * (permissions, SELinux, a path that does not exist on that host): the agent
 * scans locally with --fdpass, where the problem does not arise.
 *
 * The ScanJob is created right away (QUEUED) and linked here, so the scan is
 * visible in Jobs from the moment it is launched, not only once the agent answers.
 */
@Entity
@Table(name = "agent_commands")
public class AgentCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private ClamdEndpoint endpoint;

    /** Paths to scan, one per line. */
    @Column(nullable = false, length = 4096)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentCommandStatus status = AgentCommandStatus.PENDING;

    /**
     * SCAN: the ScanJob created up front and finished when the result arrives.
     * File actions: the alert (a finished ScanJob) whose file this acts on.
     */
    @Column(length = 64)
    private String jobId;

    // Null on rows created before file actions existed, which were all scans:
    // getType() reads null as SCAN so they need no migration.
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private AgentCommandType type;

    /** File actions only: whether the agent carried it out, and what it said. */
    private Boolean succeeded;

    @Column(length = 1024)
    private String resultMessage;

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant dispatchedAt;
    private Instant completedAt;

    public AgentCommand() {}

    public Long getId() { return id; }
    public ClamdEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(ClamdEndpoint endpoint) { this.endpoint = endpoint; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public AgentCommandStatus getStatus() { return status; }
    public void setStatus(AgentCommandStatus status) { this.status = status; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(Instant dispatchedAt) { this.dispatchedAt = dispatchedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public AgentCommandType getType() { return type != null ? type : AgentCommandType.SCAN; }
    public void setType(AgentCommandType type) { this.type = type; }
    public Boolean getSucceeded() { return succeeded; }
    public void setSucceeded(Boolean succeeded) { this.succeeded = succeeded; }
    public String getResultMessage() { return resultMessage; }
    public void setResultMessage(String resultMessage) {
        this.resultMessage = resultMessage != null && resultMessage.length() > 1024
                ? resultMessage.substring(0, 1024) : resultMessage;
    }

    /** For a file action: the file it is about (the original path, also for a restore). */
    public String getFilePath() {
        if (target == null) return null;
        if (getType() == AgentCommandType.RESTORE || getType() == AgentCommandType.RESTORE_ALLOW) {
            int nl = target.indexOf('\n');
            return nl >= 0 ? target.substring(nl + 1) : target;
        }
        return target;
    }
}
