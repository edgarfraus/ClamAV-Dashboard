package info.trizub.clamav.webclient.repo;

import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanVerdict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScanJobRepository extends JpaRepository<ScanJob, String> {
    List<ScanJob> findTop10ByOrderBySubmittedAtDesc();
    List<ScanJob> findTop200ByOrderBySubmittedAtDesc();
    List<ScanJob> findTop200ByVerdictOrderBySubmittedAtDesc(ScanVerdict verdict);
    List<ScanJob> findTop200ByStatusOrderBySubmittedAtDesc(ScanJobStatus status);
    List<ScanJob> findByVerdictOrderBySubmittedAtDesc(ScanVerdict verdict);
    List<ScanJob> findByVerdictAndAcknowledgedOrderBySubmittedAtDesc(ScanVerdict verdict, boolean acknowledged);
    long countByVerdictAndAcknowledged(ScanVerdict verdict, boolean acknowledged);
    long countByVerdict(ScanVerdict verdict);
    long countByStatus(ScanJobStatus status);
    List<ScanJob> findByStatusInOrderBySubmittedAtDesc(java.util.Collection<ScanJobStatus> statuses);
    List<ScanJob> findByEndpoint(info.trizub.clamav.webclient.model.ClamdEndpoint endpoint);

    /**
     * Jobs submitted since an instant, oldest first — the input for the
     * dashboard's time series. Bounded by the window the caller asks for
     * (14 days by default), not by the size of the table.
     */
    List<ScanJob> findBySubmittedAtGreaterThanEqualOrderBySubmittedAtAsc(java.time.Instant since);

    /** The newest job of all, used to explain an empty chart range. */
    java.util.Optional<ScanJob> findTopByOrderBySubmittedAtDesc();
}
