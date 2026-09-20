package info.trizub.clamav.webclient.util;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Turns a failed connection to a clamd endpoint into a sentence that names the
 * cause.
 *
 * <p>The clamav-client library reports every network failure as the same
 * "CommunicationException: Error while communicating with the server", and that
 * one sentence is what turns adding a device by IP into an afternoon of
 * guessing. The two most common causes need opposite fixes and are trivially
 * distinguishable — the distinction is sitting in the exception's cause chain,
 * just never shown:
 *
 * <ul>
 *   <li><b>refused</b> — the packet arrived and the machine actively answered
 *       "nothing is listening here". The firewall is not the problem; there is
 *       no clamd on that port. (A QNAP NAS behaves exactly like this: its
 *       Antivirus app uses ClamAV internally but exposes no network clamd.)</li>
 *   <li><b>timed out</b> — the packet was dropped, so nothing answered at all.
 *       That is what a firewall looks like.</li>
 * </ul>
 */
public final class ConnectionDiagnosis {

    private ConnectionDiagnosis() {
    }

    public static String explain(String host, int port, Throwable e) {
        String where = host + ":" + port;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) {
                return "Host sconosciuto (" + host + "): la console non risolve questo nome";
            }
            if (t instanceof NoRouteToHostException) {
                return "Nessuna route verso " + where + ": la console non raggiunge quella rete";
            }
            // Il timeout della console stessa (il ping ha un tetto di pochi
            // secondi): la connessione era ancora appesa, quindi i pacchetti non
            // tornano - una porta chiusa risponderebbe subito.
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
                return "Nessuna risposta da " + where + ": i pacchetti vengono scartati, "
                        + "tipicamente un firewall (una porta chiusa risponderebbe subito)";
            }
            if (t instanceof ConnectException) {
                String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
                if (m.contains("timed out") || m.contains("timeout")) {
                    return "Timeout su " + where + ": i pacchetti vengono scartati, tipicamente un firewall";
                }
                return "Connessione rifiutata su " + where + ": la macchina risponde ma non c'e' "
                        + "nessun clamd in ascolto su quella porta (non e' il firewall). "
                        + "Serve un clamd con TCPSocket/TCPAddr su quell'host, oppure un agent.";
            }
        }
        // Nessuna causa di rete riconosciuta: si riporta comunque il messaggio
        // originale invece di inventare una spiegazione.
        String msg = e != null ? (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()) : null;
        return (msg != null && !msg.isBlank() ? msg : "connessione non riuscita") + " (" + where + ")";
    }
}
