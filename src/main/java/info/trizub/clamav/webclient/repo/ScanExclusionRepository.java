package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.ScanExclusion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScanExclusionRepository extends JpaRepository<ScanExclusion, Long> {

    List<ScanExclusion> findAllByOrderByEndpointAscPathAsc();

    @Query("SELECT e FROM ScanExclusion e WHERE e.endpoint IS NULL OR e.endpoint = :ep ORDER BY e.path ASC")
    List<ScanExclusion> findApplicableTo(@Param("ep") ClamdEndpoint ep);
}
