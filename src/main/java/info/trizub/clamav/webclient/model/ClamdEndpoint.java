package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;
import xyz.capybara.clamav.Platform;

import java.time.Instant;

@Entity
@Table(name = "clamd_endpoints")
public class ClamdEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    // Host/port matter only when the console is the one contacting clamd. On an
    // agent-managed endpoint the agent contacts the console, so they stay empty.
    @Column(length = 255)
    private String host;

    @Column(nullable = false)
    private int port = 3310;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Platform platform = Platform.UNIX;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(columnDefinition = "TEXT")
    private String fullDiskTargets; // newline-separated paths; null = use platform default

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id")
    private EndpointGroup group;

    // Enrollment key of the agent installed on this machine. It replaces the
    // per-machine OPERATOR user: it is valid only for this endpoint and can only
    // reach /agent/** and POST /api/scan/report, not the whole of /api.
    // Stored in plaintext on purpose: the console must be able to regenerate the
    // installer for an existing endpoint. Visible to ADMIN only (it lives under
    // /admin/**); if it leaks, use "Rotate" to invalidate it.
    // JsonIgnore keeps it that way: every ScanJob embeds its endpoint, so without
    // it GET /api/jobs handed every agent key in the fleet to any OPERATOR. The
    // Endpoints page reads it through the getter, which Jackson does not affect.
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(length = 100, unique = true)
    private String agentKey;

    // Last time the agent checked in (command poll or report).
    private Instant agentLastSeenAt;

    // clamd VERSION string reported by the agent (same format as the VERSION
    // command on the socket), so the Endpoints page shows the version and the
    // database age without being able to connect to the machine.
    @Column(length = 255)
    private String agentClamdVersion;

    // On-access mode ("prevent"/"detect") the agent reports as ACTUALLY applied
    // on disk, read from its clamd.conf at every poll - not the one the group
    // asks for, but the confirmation that the request was carried out (or null
    // when the agent does not handle this setting yet: no realtime installed,
    // or an agent older than this feature).
    @Column(length = 16)
    private String agentOnAccessMode;

    private Instant agentOnAccessAppliedAt;

    // Operating system reported by the agent itself ("linux"/"macos"/"windows"),
    // via X-Agent-OS on every request - not the "platform" field above, which
    // comes from xyz.capybara:clamav-client and only matters for the protocol
    // to clamd (that enum has no macOS value: UNIX/WINDOWS/JVM_PLATFORM).
    // A Mac shown there as "JVM" for lack of a better choice in the dropdown
    // is a label picked by hand, not a verified fact; this field comes from
    // the machine itself and is used to pick the right default directories
    // for a full scan (e.g. /Users on macOS, where /home is only an
    // automounter stub).
    @Column(length = 16)
    private String agentOs;

    // What the installed agent says it can do, from X-Agent-Capabilities on
    // every request (comma-separated, e.g. "file-actions"). An agent too old to
    // send the header has none, and the console must not hand it a command it
    // would misread: an old agent treats every command as a scan.
    @Column(length = 255)
    private String agentCapabilities;

    public ClamdEndpoint() {}

    public ClamdEndpoint(String name, String host, int port, Platform platform) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.platform = platform;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public Platform getPlatform() { return platform; }
    public void setPlatform(Platform platform) { this.platform = platform; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getFullDiskTargets() { return fullDiskTargets; }
    public void setFullDiskTargets(String fullDiskTargets) { this.fullDiskTargets = fullDiskTargets; }
    public EndpointGroup getGroup() { return group; }
    public void setGroup(EndpointGroup group) { this.group = group; }
    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
    public Instant getAgentLastSeenAt() { return agentLastSeenAt; }
    public void setAgentLastSeenAt(Instant agentLastSeenAt) { this.agentLastSeenAt = agentLastSeenAt; }
    public String getAgentClamdVersion() { return agentClamdVersion; }
    public void setAgentClamdVersion(String agentClamdVersion) { this.agentClamdVersion = agentClamdVersion; }
    public String getAgentOnAccessMode() { return agentOnAccessMode; }
    public void setAgentOnAccessMode(String agentOnAccessMode) { this.agentOnAccessMode = agentOnAccessMode; }
    public Instant getAgentOnAccessAppliedAt() { return agentOnAccessAppliedAt; }
    public void setAgentOnAccessAppliedAt(Instant agentOnAccessAppliedAt) { this.agentOnAccessAppliedAt = agentOnAccessAppliedAt; }
    public String getAgentOs() { return agentOs; }
    public void setAgentOs(String agentOs) { this.agentOs = agentOs; }
    public String getAgentCapabilities() { return agentCapabilities; }
    public void setAgentCapabilities(String agentCapabilities) { this.agentCapabilities = agentCapabilities; }
    public boolean isAgentEnrolled() { return agentKey != null && !agentKey.isBlank(); }

    /** True when the installed agent can quarantine and restore single files on request. */
    public boolean isFileActionsSupported() {
        if (!isAgentEnrolled() || agentCapabilities == null) return false;
        for (String c : agentCapabilities.split(",")) {
            if ("file-actions".equals(c.trim())) return true;
        }
        return false;
    }
}
