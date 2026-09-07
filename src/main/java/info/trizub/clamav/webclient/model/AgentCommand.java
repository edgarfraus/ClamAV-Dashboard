package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Una scansione che la console ha chiesto all'agent di eseguire sulla propria
 * macchina. Esiste perche' le scansioni PATH via TCP falliscono quando clamd non
 * puo' leggere il path (permessi, SELinux, path inesistente su quell'host):
 * l'agent scansiona in locale con --fdpass, dove il problema non si pone.
 *
 * Il ScanJob viene creato subito (QUEUED) e collegato qui, cosi' la scansione e'
 * visibile in Jobs dal momento in cui la lanci, non solo quando l'agent risponde.
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

    /** Path da scansionare, uno per riga. */
    @Column(nullable = false, length = 4096)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentCommandStatus status = AgentCommandStatus.PENDING;

    /** ScanJob creato in anticipo e completato quando arriva l'esito. */
    @Column(length = 64)
    private String jobId;

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
}
