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
}
