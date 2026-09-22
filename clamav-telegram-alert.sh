#!/bin/bash
#
# clamav-telegram-alert.sh
#
# Runs a scan through clamd (much faster than a cold clamscan) and, if infected
# files are found (or the scan fails), sends the result to the ClamAV Dashboard
# via POST /api/scan/report. The dashboard is what sends the Telegram alert
# (Admin > Settings > Telegram notifications), so the bot token and chat id live
# in exactly one place.
#
# Meant to be run periodically (systemd timer, launchd, cron).
#
# Requirements:
#   - clamd running, or clamscan installed as a fallback
#   - curl
#
# CONFIGURATION: /etc/clamav/console-report.conf (chmod 600), written by the
# agent installer:
#   DASHBOARD_URL='https://console.example.com'
#   DASHBOARD_AGENT_KEY='cav_...'
#   SCAN_PATHS_LIST='/home /srv'                          # optional, overrides the defaults
#   DASHBOARD_CA_BUNDLE='/etc/ssl/certs/internal-ca.pem'  # optional, private CA
#   DASHBOARD_INSECURE=1                                  # optional, skip TLS verification
#
set -euo pipefail

# On macOS (install_macos() in install.sh.tpl installs this same script under
# a LaunchDaemon as clamav-scan-report.sh) this runs with launchd's own
# minimal PATH, not the interactive shell PATH "brew shellenv" sets up - so
# clamscan/clamdscan, installed under Homebrew's prefix, are invisible to
# "command -v" here even though they work fine from a Terminal. No-op on
# Linux, where these paths don't exist.
export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/local/sbin:$PATH"

### === CONFIGURATION === ###

# The installer's configuration takes precedence when present, so this script
# and the on-access one share the same URL and credentials.
CONFIG_FILE="${CONFIG_FILE:-/etc/clamav/console-report.conf}"
[[ -r "$CONFIG_FILE" ]] && . "$CONFIG_FILE"

DASHBOARD_URL="${DASHBOARD_URL:-http://localhost:8080}"
DASHBOARD_AGENT_KEY="${DASHBOARD_AGENT_KEY:-}"
DASHBOARD_API_USER="${DASHBOARD_API_USER:-}"
DASHBOARD_API_PASSWORD="${DASHBOARD_API_PASSWORD:-}"
DASHBOARD_CA_BUNDLE="${DASHBOARD_CA_BUNDLE:-}"
DASHBOARD_INSECURE="${DASHBOARD_INSECURE:-0}"

# Where the scanners actually live. ClamAV is not always on PATH: on an appliance
# - a QNAP or Synology NAS, a router distro - it ships inside an application
# package under a path nothing exports, e.g.
# /share/CACHEDEV1_DATA/.qpkg/ClamAV/bin/clamscan. Without a way to name the
# binary this script can only answer "Neither clamdscan nor clamscan is
# installed" on a machine where ClamAV is installed and working perfectly well.
# Set CLAMSCAN_BIN / CLAMDSCAN_BIN in the config file on those.
CLAMDSCAN_BIN="${CLAMDSCAN_BIN:-clamdscan}"
CLAMSCAN_BIN="${CLAMSCAN_BIN:-clamscan}"

# Endpoint key (preferred) or, alternatively, an OPERATOR user.
if [[ -n "$DASHBOARD_AGENT_KEY" ]]; then
  AUTH_ARGS=(-H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}")
elif [[ -n "$DASHBOARD_API_USER" && -n "$DASHBOARD_API_PASSWORD" ]]; then
  AUTH_ARGS=(-u "${DASHBOARD_API_USER}:${DASHBOARD_API_PASSWORD}")
else
  echo "ERROR: DASHBOARD_AGENT_KEY (or DASHBOARD_API_USER + DASHBOARD_API_PASSWORD) is required." >&2
  exit 1
fi

TLS_ARGS=()
[[ -n "$DASHBOARD_CA_BUNDLE" ]] && TLS_ARGS+=(--cacert "$DASHBOARD_CA_BUNDLE")
[[ "$DASHBOARD_INSECURE" == "1" ]] && TLS_ARGS+=(--insecure)

# Paths to scan. The installer can impose them by writing SCAN_PATHS_LIST into
# the configuration file (on macOS, for instance, the Linux paths do not exist).
if [[ -n "${SCAN_PATHS_LIST:-}" ]]; then
  read -r -a SCAN_PATHS <<< "$SCAN_PATHS_LIST"
else
  # Avoid /proc, /sys and slow network mounts.
  SCAN_PATHS=(
    "/home"
    "/tmp"
    "/var/www"
    "/opt"
  )
fi

# Drop targets that don't exist on THIS machine: clamdscan/clamscan given
# several paths at once return a nonzero exit code as soon as ANY of them is
# missing, which would otherwise mark the entire scan as failed even though
# every real directory scanned clean. The default list above (and any
# admin-configured SCAN_PATHS_LIST) is meant to cover a whole fleet, so not
# every machine has every path - /var/www only existing on web servers is
# exactly this in practice.
EXISTING_SCAN_PATHS=()
MISSING_SCAN_PATHS=()
for p in "${SCAN_PATHS[@]}"; do
  if [[ -e "$p" ]]; then EXISTING_SCAN_PATHS+=("$p"); else MISSING_SCAN_PATHS+=("$p"); fi
done
if [[ ${#MISSING_SCAN_PATHS[@]} -gt 0 ]]; then
  echo "[$TIMESTAMP] skipping missing path(s): ${MISSING_SCAN_PATHS[*]}" >> "$LOG_FILE"
fi
SCAN_PATHS=("${EXISTING_SCAN_PATHS[@]}")

# Paths to exclude (regexes, as accepted by clamscan's --exclude-dir).
#
# These only take effect when clamscan is the scanner: clamdscan - preferred
# below, because clamd already holds the signatures in memory - does not support
# --exclude-dir at all and answers "WARNING: Ignoring unsupported option". To
# exclude a path from a clamd-backed scan for real, use ExcludePath in
# clamd.conf. The quarantine entry is therefore belt and braces: what actually
# keeps quarantined files from being reported again is the filter below.
EXCLUDE_DIRS=(
  "^/var/lib/docker"
  "\.git"
  "node_modules"
  "^/home/[^/]+/\.cache"
  "\.claimav-quarantine"
)

# Local log file (history, independent of the dashboard)
LOG_FILE="${LOG_FILE:-/var/log/clamav-scan-report.log}"

### === END OF CONFIGURATION === ###

TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"
HOSTNAME="$(hostname)"

EXCLUDE_ARGS=()
for pattern in "${EXCLUDE_DIRS[@]}"; do
  EXCLUDE_ARGS+=(--exclude-dir="${pattern}")
done

echo "[$TIMESTAMP] starting scan of: ${SCAN_PATHS[*]}" >> "$LOG_FILE"

NO_SCANNER=0
NO_SCANNER_REASON=""

if [[ ${#SCAN_PATHS[@]} -eq 0 ]]; then
  NO_SCANNER_REASON="None of the configured scan paths exist on this machine."
  echo "[$TIMESTAMP] $NO_SCANNER_REASON" >> "$LOG_FILE"
  NO_SCANNER=1
# clamdscan needs clamd to be running. Where it is not (typically macOS with
# ClamAV from Homebrew, where the daemon does not start on its own) it falls
# back to clamscan, which is self-contained: slower, but the scan still happens.
elif command -v "$CLAMDSCAN_BIN" >/dev/null 2>&1; then
  if "$CLAMDSCAN_BIN" --ping 1 >/dev/null 2>&1; then
    # --fdpass needs a UNIX-domain socket to clamd (SCM_RIGHTS cannot cross
    # TCP at all, at the kernel level): a stock Homebrew clamd on macOS, or
    # any clamd without LocalSocket in its config, makes every --fdpass scan
    # fail outright and identically no matter what is targeted. Verified
    # against this very script (always present, readable, never infected).
    FDPASS_RC=0
    "$CLAMDSCAN_BIN" --fdpass "$0" >/dev/null 2>&1 || FDPASS_RC=$?
    if [ "$FDPASS_RC" -le 1 ]; then
      SCANNER=("$CLAMDSCAN_BIN" --multiscan --fdpass --infected)
    else
      SCANNER=("$CLAMDSCAN_BIN" --multiscan --infected)
    fi
  elif command -v "$CLAMSCAN_BIN" >/dev/null 2>&1; then
    SCANNER=("$CLAMSCAN_BIN" --recursive --infected)
    echo "[$TIMESTAMP] clamd unreachable: falling back to clamscan (slower)" >> "$LOG_FILE"
  else
    # Two different problems that need two different fixes (start the daemon
    # vs. install the fallback package): reporting them as one generic
    # "neither is installed" sends whoever reads it to the wrong place, when
    # clamdscan being present at all already rules that half out.
    NO_SCANNER_REASON="clamdscan is installed but clamd is not responding to --ping (and clamscan is not installed as a fallback)."
    echo "[$TIMESTAMP] $NO_SCANNER_REASON" >> "$LOG_FILE"
    NO_SCANNER=1
  fi
elif command -v "$CLAMSCAN_BIN" >/dev/null 2>&1; then
  SCANNER=("$CLAMSCAN_BIN" --recursive --infected)
else
  NO_SCANNER_REASON="Neither clamdscan nor clamscan was found ($CLAMDSCAN_BIN / $CLAMSCAN_BIN). On an appliance where ClamAV lives outside PATH, set CLAMSCAN_BIN in $CONFIG_FILE."
  echo "[$TIMESTAMP] $NO_SCANNER_REASON" >> "$LOG_FILE"
  NO_SCANNER=1
fi

# set +e/-e around the call: with "set -e" active, a non-zero exit code
# (1 = infected files found, 2 = error) would end the script BEFORE $? is read,
# so it is disabled for this command only.
if [ "$NO_SCANNER" -eq 0 ]; then
  set +e
  SCAN_OUTPUT="$("${SCANNER[@]}" \
    "${EXCLUDE_ARGS[@]}" \
    "${SCAN_PATHS[@]}" 2>&1)"
  EXIT_CODE=$?
  set -e
else
  # No scanner: report it as a scan error, so the console sees the problem
  # instead of simply never hearing from this machine again.
  SCAN_OUTPUT="$NO_SCANNER_REASON"
  EXIT_CODE=2
fi

echo "$SCAN_OUTPUT" >> "$LOG_FILE"
echo "[$TIMESTAMP] scan finished with exit code $EXIT_CODE" >> "$LOG_FILE"

# Keep only the lines reporting infected files (format: "path: SIGNATURE FOUND")
INFECTED_LINES="$(echo "$SCAN_OUTPUT" | grep "FOUND$" || true)"

# Files already sitting in quarantine are re-detections of something ClamAV has
# already dealt with: the scan simply walked over the quarantine directory, which
# lives on the same filesystem as the files it holds and so sits inside a scanned
# tree by construction. Reporting them makes the console alert again, on every
# scheduled scan, forever, about a threat that was neutralised the first time.
# Filtered here rather than by --exclude-dir above because that option does
# nothing whenever clamdscan is the scanner.
if [ -n "$INFECTED_LINES" ]; then
  KEPT_LINES=""
  SKIPPED_QUARANTINED=0
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    case "${line%%: *}" in
      */.claimav-quarantine/*)
        SKIPPED_QUARANTINED=$((SKIPPED_QUARANTINED + 1))
        continue
        ;;
    esac
    [ -n "$KEPT_LINES" ] && KEPT_LINES+=$'\n'
    KEPT_LINES+="$line"
  done <<< "$INFECTED_LINES"
  if [ "$SKIPPED_QUARANTINED" -gt 0 ]; then
    echo "[$TIMESTAMP] ignoring ${SKIPPED_QUARANTINED} detection(s) already in quarantine" >> "$LOG_FILE"
  fi
  INFECTED_LINES="$KEPT_LINES"
fi

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  printf '%s' "$s"
}

# ClamAV version, so the console can show the signature database age without
# connecting to this machine.
# The version string the console displays as this machine's signature level.
#
# Prefer whichever tool reports the SIGNATURES and not just the engine:
# "ClamAV 1.5.4/28129/Sat Sep 20 ..." carries the database version and its date,
# a bare "ClamAV 1.5.4" carries neither. clamdscan prints the bare form whenever
# clamd is not answering - it is then reporting itself, the client - and clamscan
# prints the bare form when no database is loaded at all. Taking the first
# non-empty answer would therefore show a healthy-looking version number for a
# machine whose signatures are months old, or missing entirely.
clamav_version() {
  local v="" w=""
  command -v "$CLAMDSCAN_BIN" >/dev/null 2>&1 && v="$("$CLAMDSCAN_BIN" --version 2>/dev/null | head -1)"
  command -v "$CLAMSCAN_BIN"  >/dev/null 2>&1 && w="$("$CLAMSCAN_BIN"  --version 2>/dev/null | head -1)"
  case "$v" in */*) printf '%s' "$v"; return ;; esac
  case "$w" in */*) printf '%s' "$w"; return ;; esac
  printf '%s' "${v:-$w}"
}

# So the console knows what this machine actually is - see
# clamav-agent-poll.sh's agent_os() for the full reasoning (the Platform
# field has no macOS value to pick).
agent_os() {
  case "$(uname -s)" in
    Linux)  printf 'linux' ;;
    Darwin) printf 'macos' ;;
  esac
}

# Build the JSON array of infected lines (one raw string per line; parsing
# "path: SIGNATURE FOUND" is done server-side by the dashboard).
FINDINGS_JSON="[]"
if [ -n "$INFECTED_LINES" ]; then
  FINDINGS_JSON="["
  first=true
  while IFS= read -r line; do
    if [ "$first" = true ]; then first=false; else FINDINGS_JSON+=","; fi
    FINDINGS_JSON+="\"$(json_escape "$line")\""
  done <<< "$INFECTED_LINES"
  FINDINGS_JSON+="]"
fi

# clamdscan prints the real error before the summary: pick the error lines,
# otherwise the report carries "Infected files: 0" instead of the cause.
scan_error_detail() {
  local out="$1" detail
  detail="$(printf '%s\n' "$out" | grep -iE "^ERROR:|Can't access|Permission denied|lstat\(\) failed|No such file" | head -3)"
  [[ -z "$detail" ]] && detail="$(printf '%s\n' "$out" | tail -3)"
  printf '%s' "$detail"
}

send_report() {
  local verdict="$1"
  local error_message="$2"
  local payload
  payload="{\"hostname\":\"$(json_escape "$HOSTNAME")\",\"path\":\"$(json_escape "${SCAN_PATHS[*]}")\",\"verdict\":\"${verdict}\",\"findings\":${FINDINGS_JSON},\"errorMessage\":\"$(json_escape "$error_message")\"}"

  curl -s "${AUTH_ARGS[@]}" "${TLS_ARGS[@]+"${TLS_ARGS[@]}"}" \
    -H "X-Agent-Clamav: $(clamav_version)" \
    -H "X-Agent-OS: $(agent_os)" \
    -X POST "${DASHBOARD_URL%/}/api/scan/report" \
    -H 'Content-Type: application/json' \
    -d "$payload" \
    > /dev/null
}

if printf '%s\n' "$SCAN_OUTPUT" | grep -qE '^Known viruses: 0$'; then
  # No signatures loaded: clamscan exits 2 but still prints "Infected files: 0",
  # so without this the branch below would file it as an ordinary clean scan and
  # this machine would look protected while scanning against nothing at all.
  send_report "ERROR" "No signature database on this machine (Known viruses: 0): ${SCAN_PATHS[*]} was not actually scanned. Run freshclam here."
  echo "[$TIMESTAMP] NO signature database - error report sent" >> "$LOG_FILE"

elif [ -n "$INFECTED_LINES" ]; then
  COUNT="$(echo "$INFECTED_LINES" | wc -l)"
  send_report "VIRUS_FOUND" ""
  echo "[$TIMESTAMP] report sent to the dashboard (${COUNT} infected files)" >> "$LOG_FILE"

elif [ "$EXIT_CODE" -eq 2 ] && ! printf '%s\n' "$SCAN_OUTPUT" | grep -qE '^Infected files: 0$'; then
  # clamdscan/clamscan exit 2 for two very different situations it cannot
  # tell apart in its own exit code: the scan never really ran (clamd died
  # mid-scan, a bad target...) and "it ran to completion, found nothing, but
  # could not open some individual file along the way" (a permission-
  # protected system file, a socket, a SIP-restricted path on macOS...). The
  # summary line only gets printed once a scan has actually finished, so its
  # absence here means this is the first, genuine kind of failure.
  send_report "ERROR" "Scan failed (exit code ${EXIT_CODE}) on ${SCAN_PATHS[*]}: $(scan_error_detail "$SCAN_OUTPUT")"
  echo "[$TIMESTAMP] scan error, warning report sent to the dashboard" >> "$LOG_FILE"

else
  # Either a clean exit 0, or exit 2 with a completed summary and zero
  # infections - ordinary noise on a broad target, not worth an alert on a
  # scheduled scan whose whole point is to stay quiet when nothing is wrong.
  echo "[$TIMESTAMP] nothing infected, no report sent" >> "$LOG_FILE"
fi

exit 0
