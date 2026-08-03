package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scheduled_scans")
public class ScheduledScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 128)
    private String cronExpression;

    @Column(nullable = false, length = 2048)
    private String scanPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScheduledScanTargetType targetType = ScheduledScanTargetType.ENDPOINT;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "endpoint_id")
    private ClamdEndpoint endpoint;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "scan_group_id")
    private EndpointGroup endpointGroup;

    @Column(nullable = false)
    private boolean enabled = true;

    private Instant lastRunAt;

    @Column(length = 64)
    private String createdBy;

    private Instant createdAt = Instant.now();

    public ScheduledScan() {}

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public String getScanPath() { return scanPath; }
    public void setScanPath(String scanPath) { this.scanPath = scanPath; }
    public ScheduledScanTargetType getTargetType() { return targetType; }
    public void setTargetType(ScheduledScanTargetType targetType) { this.targetType = targetType; }
    public ClamdEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(ClamdEndpoint endpoint) { this.endpoint = endpoint; }
    public EndpointGroup getEndpointGroup() { return endpointGroup; }
    public void setEndpointGroup(EndpointGroup endpointGroup) { this.endpointGroup = endpointGroup; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
