package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.EndpointGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClamdEndpointRepository extends JpaRepository<ClamdEndpoint, Long> {
    Optional<ClamdEndpoint> findByName(String name);
    List<ClamdEndpoint> findByGroup(EndpointGroup group);
    List<ClamdEndpoint> findByGroupAndEnabled(EndpointGroup group, boolean enabled);
    List<ClamdEndpoint> findByEnabledTrue();
}
