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
                return "Unknown host (" + host + "): the console cannot resolve this name";
            }
            if (t instanceof NoRouteToHostException) {
                return "No route to " + where + ": the console cannot reach that network";
            }
            // The console's own timeout (the ping is capped at a few seconds):
            // the connection was still hanging, so the packets are not coming
            // back — a closed port would answer immediately.
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
                return "No answer from " + where + ": packets are being dropped, "
                        + "typically a firewall (a closed port would answer immediately)";
            }
            if (t instanceof ConnectException) {
                String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
                if (m.contains("timed out") || m.contains("timeout")) {
                    return "Timed out on " + where + ": packets are being dropped, typically a firewall";
                }
                return "Connection refused on " + where + ": the machine answers but nothing "
                        + "is listening on that port (this is not the firewall). "
                        + "That host needs a clamd with TCPSocket/TCPAddr, or an agent.";
            }
        }
        // No recognised network cause: report the original message rather
        // than inventing an explanation.
        String msg = e != null ? (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()) : null;
        return (msg != null && !msg.isBlank() ? msg : "connection failed") + " (" + where + ")";
    }
}
