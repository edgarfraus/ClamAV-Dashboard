package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.EndpointGroup;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import info.trizub.clamav.webclient.repo.EndpointGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EndpointGroupService {

    private final EndpointGroupRepository repo;
    private final ClamdEndpointRepository endpointRepo;

    public EndpointGroupService(EndpointGroupRepository repo, ClamdEndpointRepository endpointRepo) {
        this.repo = repo;
        this.endpointRepo = endpointRepo;
    }

    public List<EndpointGroup> all() { return repo.findAll(); }

    public EndpointGroup get(Long id) { return repo.findById(id).orElseThrow(); }

    @Transactional
    public EndpointGroup create(String name, String description) {
        return repo.save(new EndpointGroup(name, description));
    }

    @Transactional
    public EndpointGroup update(Long id, String name, String description) {
        EndpointGroup g = repo.findById(id).orElseThrow();
        g.setName(name);
        g.setDescription(description);
        return repo.save(g);
    }

    /**
     * Modalita' realtime desiderata per tutti gli endpoint del gruppo. La
     * console non la applica direttamente: l'agent la legge al prossimo poll
     * (GET /api/agent/commands) e la applica in locale sul proprio clamd.conf,
     * quindi il cambio non e' immediato e riguarda solo endpoint Linux con
     * on-access gia' installato.
     */
    @Transactional
    public void setOnAccessPrevent(Long id, boolean prevent) {
        EndpointGroup g = repo.findById(id).orElseThrow();
        g.setOnAccessPrevent(prevent);
        repo.save(g);
    }

    @Transactional
    public void delete(Long id) {
        EndpointGroup g = repo.findById(id).orElseThrow();
        // Unassign all endpoints from this group before deleting
        for (ClamdEndpoint ep : endpointRepo.findByGroup(g)) {
            ep.setGroup(null);
            endpointRepo.save(ep);
        }
        repo.delete(g);
    }

    public List<ClamdEndpoint> endpointsInGroup(Long groupId) {
        EndpointGroup g = repo.findById(groupId).orElseThrow();
        return endpointRepo.findByGroup(g);
    }
}
