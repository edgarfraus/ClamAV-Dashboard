package info.trizub.clamav.webclient.model;

/**
 * What actually happened to an infected file after detection. Only meaningful
 * for VIRUS_FOUND jobs - NOT_ATTEMPTED covers both "endpoint's group is in
 * Detection mode" (by design, nothing is touched) and "no remediation was
 * ever wired up for this job's path".
 */
public enum RemediationStatus {
    NOT_ATTEMPTED,
    QUARANTINED,
    REMOVED,
    FAILED
}
