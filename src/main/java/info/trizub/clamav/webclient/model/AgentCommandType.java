package info.trizub.clamav.webclient.model;

/**
 * What an agent command asks the machine to do. Everything except SCAN acts on
 * one file of an alert and is only ever sent to an agent that advertised the
 * "file-actions" capability: an older agent would read any command as a scan.
 */
public enum AgentCommandType {
    /** Scan the target paths (one per line) and report through /api/scan/report. */
    SCAN,
    /** Move one detected file into quarantine; the agent re-scans it first and refuses if it is no longer detected. */
    QUARANTINE,
    /** Move a file back from quarantine to where it was found. Target: "quarantine path\noriginal path". */
    RESTORE,
    /** As RESTORE, and add the file's SHA-256 to ClamAV's local allow list so it is not detected again. */
    RESTORE_ALLOW;

    public boolean isFileAction() { return this != SCAN; }
}
