package info.trizub.clamav.webclient.web;

/**
 * One row of the dashboard's verdict breakdown.
 *
 * <p>A record rather than a Map so the template's {@code row.percent} is a
 * real accessor the compiler checks, and so the percentage is computed once
 * here instead of in three places in Thymeleaf.
 *
 * @param label   what the user reads ("Clean", "Threats", …)
 * @param count   how many jobs ended with this verdict
 * @param percent share of all tracked jobs, already rounded, 0 when there are none
 * @param badge   suffix of the badge class, e.g. "ok" for .badge-ok
 * @param bar     CoreUI colour for the progress bar, e.g. "success" for .bg-success
 */
public record VerdictRow(String label, long count, long percent, String badge, String bar) {

    public static VerdictRow of(String label, long count, long total, String badge, String bar) {
        long percent = total > 0 ? Math.round(count * 100.0 / total) : 0;
        return new VerdictRow(label, count, percent, badge, bar);
    }
}
