package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scan_exclusions")
public class ScanExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "endpoint_id")
    private ClamdEndpoint endpoint; // null = applies to all endpoints

    @Column(nullable = false, length = 512)
    private String path;

    @Column(length = 256)
    private String reason;

    private Instant createdAt = Instant.now();

    @Column(length = 64)
    private String createdBy;

    public ScanExclusion() {}

    public Long getId() { return id; }
    public ClamdEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(ClamdEndpoint endpoint) { this.endpoint = endpoint; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
