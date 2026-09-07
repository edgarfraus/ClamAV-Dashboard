package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ScheduledScan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduledScanRepository extends JpaRepository<ScheduledScan, Long> {
    List<ScheduledScan> findByEnabledTrue();
    List<ScheduledScan> findByEndpoint(info.trizub.clamav.webclient.model.ClamdEndpoint endpoint);
}
