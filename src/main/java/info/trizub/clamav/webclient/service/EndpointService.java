package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.EndpointGroup;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import info.trizub.clamav.webclient.repo.EndpointGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.capybara.clamav.Platform;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class EndpointService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClamdEndpointRepository repo;
    private final SettingsService settings;
    private final EndpointGroupRepository groupRepo;

    public EndpointService(ClamdEndpointRepository repo, SettingsService settings,
                           EndpointGroupRepository groupRepo) {
        this.repo = repo;
        this.settings = settings;
        this.groupRepo = groupRepo;
    }

    @Transactional
    public void ensureDefaultEndpoint() {
        if (repo.count() == 0) {
            Platform p;
            try { p = Platform.valueOf(settings.legacyPlatform()); } catch (Exception e) { p = Platform.UNIX; }
            ClamdEndpoint ep = new ClamdEndpoint("default", settings.legacyHost(), settings.legacyPort(), p);
            repo.save(ep);
        }
    }

    public List<ClamdEndpoint> all() {
        return repo.findAll();
    }

    public ClamdEndpoint get(Long id) {
        return repo.findById(id).orElseThrow();
    }

    @Transactional
    public ClamdEndpoint create(String name, String host, int port, Platform platform, boolean enabled) {
        ClamdEndpoint ep = new ClamdEndpoint(name, host, port, platform);
        ep.setEnabled(enabled);
        return repo.save(ep);
    }

    @Transactional
    public ClamdEndpoint update(Long id, String name, String host, int port, Platform platform, boolean enabled) {
        ClamdEndpoint ep = repo.findById(id).orElseThrow();
        ep.setName(name);
        ep.setHost(host);
        ep.setPort(port);
        ep.setPlatform(platform);
        ep.setEnabled(enabled);
        return repo.save(ep);
    }

    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
    }

    /**
     * Genera (o rigenera) la chiave di enrollment dell'agent per un endpoint.
     * Rigenerare invalida immediatamente la chiave precedente: gli agent gia'
     * installati con quella vecchia smettono di essere accettati finche' non
     * vengono reinstallati con la nuova.
     */
    @Transactional
    public String generateAgentKey(Long id) {
        ClamdEndpoint ep = repo.findById(id).orElseThrow();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String key = "cav_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        ep.setAgentKey(key);
        ep.setAgentLastSeenAt(null);
        repo.save(ep);
        return key;
    }

    @Transactional
    public void revokeAgentKey(Long id) {
        ClamdEndpoint ep = repo.findById(id).orElseThrow();
        ep.setAgentKey(null);
        ep.setAgentLastSeenAt(null);
        repo.save(ep);
    }

    public Optional<ClamdEndpoint> findByAgentKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return repo.findByAgentKey(key.trim());
    }

    /** Aggiorna il "last seen" dell'agent. Best-effort: non deve mai far fallire la richiesta. */
    @Transactional
    public void touchAgentSeen(Long id) {
        try {
            repo.findById(id).ifPresent(ep -> {
                ep.setAgentLastSeenAt(Instant.now());
                repo.save(ep);
            });
        } catch (Exception ignored) {
        }
    }

    @Transactional
    public void saveScanTargets(Long id, String targets) {
        ClamdEndpoint ep = repo.findById(id).orElseThrow();
        ep.setFullDiskTargets(targets == null || targets.isBlank() ? null : targets);
        repo.save(ep);
    }

    @Transactional
    public void setGroup(Long endpointId, Long groupId) {
        ClamdEndpoint ep = repo.findById(endpointId).orElseThrow();
        if (groupId == null) {
            ep.setGroup(null);
        } else {
            EndpointGroup g = groupRepo.findById(groupId).orElse(null);
            ep.setGroup(g);
        }
        repo.save(ep);
    }

    public ClamdEndpoint defaultEndpoint() {
        return repo.findByName("default").orElseGet(() -> repo.findAll().stream().findFirst().orElse(null));
    }

    /**
     * Like defaultEndpoint(), but guarantees a non-null return by creating the default endpoint when needed.
     */
    @Transactional
    public ClamdEndpoint defaultEndpointOrEnsure() {
        ClamdEndpoint ep = defaultEndpoint();
        if (ep == null) {
            ensureDefaultEndpoint();
            ep = defaultEndpoint();
        }
        return ep;
    }
}
