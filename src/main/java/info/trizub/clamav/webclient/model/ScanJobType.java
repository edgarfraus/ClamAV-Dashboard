package info.trizub.clamav.webclient.model;

public enum ScanJobType {
    UPLOAD,
    PATH,
    WATCH,
    // Batch scan run on a fleet machine and reported via /api/scan/report
    EXTERNAL,
    // On-access (clamonacc) detection, reported the moment the file was touched
    REALTIME,
    // Scan dispatched to the machine's agent through the command queue and run
    // locally there. Never handed to this app's executor.
    AGENT
}
