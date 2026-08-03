package info.trizub.clamav.webclient.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

public class ClamVersionInfo {

    public final boolean online;
    public final String raw;
    public final String clamVersion;
    public final String dbVersion;
    public final String dbDate;
    public final long dbAgeDays;
    public final boolean stale;
    public final String error;

    private ClamVersionInfo(boolean online, String raw, String clamVersion,
                            String dbVersion, String dbDate, long dbAgeDays,
                            boolean stale, String error) {
        this.online = online;
        this.raw = raw;
        this.clamVersion = clamVersion;
        this.dbVersion = dbVersion;
        this.dbDate = dbDate;
        this.dbAgeDays = dbAgeDays;
        this.stale = stale;
        this.error = error;
    }

    public static ClamVersionInfo offline(String error) {
        return new ClamVersionInfo(false, null, null, null, null, -1, false, error);
    }

    public static ClamVersionInfo parse(String raw) {
        if (raw == null) return offline("No version returned");
        try {
            // Format: "ClamAV 1.0.3/27161/Mon Feb  5 08:30:00 2024"
            String[] parts = raw.split("/", 3);
            if (parts.length != 3) return new ClamVersionInfo(true, raw, raw, null, null, -1, false, null);
            String clamVer = parts[0].replace("ClamAV", "").trim();
            String dbVer = parts[1].trim();
            String dbDate = parts[2].trim();
            long ageDays = parseAgeDays(dbDate);
            return new ClamVersionInfo(true, raw, clamVer, dbVer, dbDate, ageDays, ageDays > 7 && ageDays >= 0, null);
        } catch (Exception e) {
            return new ClamVersionInfo(true, raw, raw, null, null, -1, false, null);
        }
    }

    private static long parseAgeDays(String dateStr) {
        try {
            // Normalize multiple spaces ("Mon Feb  5 ..." -> "Mon Feb 5 ...")
            String normalized = dateStr.replaceAll(" {2,}", " ").trim();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ENGLISH);
            LocalDate date = LocalDate.parse(normalized, fmt);
            return ChronoUnit.DAYS.between(date, LocalDate.now());
        } catch (Exception e) {
            return -1;
        }
    }
}
