package info.trizub.clamav.webclient.model;

public enum AgentCommandStatus {
    /** In attesa che l'agent la ritiri al prossimo poll. */
    PENDING,
    /** Ritirata dall'agent, scansione in corso sulla macchina. */
    DISPATCHED,
    /** L'agent ha inviato l'esito. */
    DONE
}
