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

    // Host/porta servono solo quando e' la console a contattare clamd. Su un
    // endpoint con agent e' l'agent a contattare la console, quindi restano vuoti.
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

    // Chiave di enrollment dell'agent installato su questa macchina. Sostituisce
    // l'utente OPERATOR per-macchina: vale solo per questo endpoint e puo' fare
    // solo /agent/** e POST /api/scan/report, non tutta la /api.
    // E' salvata in chiaro di proposito: la console deve poter rigenerare lo
    // script di installazione per un endpoint gia' creato. Visibile solo ad ADMIN
    // (sta sotto /admin/**); se trapela, usa "Rotate" per invalidarla.
    @Column(length = 100, unique = true)
    private String agentKey;

    // Ultima volta che l'agent si e' fatto vivo (poll dei comandi o report).
    private Instant agentLastSeenAt;

    // Stringa VERSION di clamd riportata dall'agent (stesso formato del comando
    // VERSION su socket), cosi' la pagina Endpoints mostra versione ed eta' del
    // database anche senza potersi collegare alla macchina.
    @Column(length = 255)
    private String agentClamdVersion;

    // Modalita' on-access ("prevent"/"detect") che l'agent ha riportato di avere
    // REALMENTE applicata sul disco, letta dal suo clamd.conf a ogni poll - non
    // e' quella richiesta dal gruppo, e' la conferma che la richiesta e' stata
    // eseguita (o null se l'agent non gestisce ancora questa impostazione: nessun
    // realtime installato, oppure versione dell'agent precedente a questa funzione).
    @Column(length = 16)
    private String agentOnAccessMode;

    private Instant agentOnAccessAppliedAt;

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
    public boolean isAgentEnrolled() { return agentKey != null && !agentKey.isBlank(); }
}
