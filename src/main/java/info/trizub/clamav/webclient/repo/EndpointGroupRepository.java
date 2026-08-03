package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.EndpointGroup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EndpointGroupRepository extends JpaRepository<EndpointGroup, Long> {
}
