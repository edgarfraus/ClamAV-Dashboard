#!/bin/bash
#
# clamav-telegram-alert.sh
#
# Runs a scan through clamd (much faster than a cold clamscan) and, if infected
# files are found (or the scan fails), sends the result to the ClaimAV Dashboard
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

# Paths to exclude (regexes, as accepted by clamdscan --exclude-dir)
EXCLUDE_DIRS=(
  "^/var/lib/docker"
  "\.git"
  "node_modules"
  "^/home/[^/]+/\.cache"
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
elif command -v clamdscan >/dev/null 2>&1; then
  if clamdscan --ping 1 >/dev/null 2>&1; then
    SCANNER=(clamdscan --multiscan --fdpass --infected)
  elif command -v clamscan >/dev/null 2>&1; then
    SCANNER=(clamscan --recursive --infected)
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
elif command -v clamscan >/dev/null 2>&1; then
  SCANNER=(clamscan --recursive --infected)
else
  NO_SCANNER_REASON="Neither clamdscan nor clamscan is installed on this machine."
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

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  printf '%s' "$s"
}

# ClamAV version, so the console can show the signature database age without
# connecting to this machine.
clamav_version() {
  local v=""
  command -v clamdscan >/dev/null 2>&1 && v="$(clamdscan --version 2>/dev/null | head -1)"
  [[ -z "$v" ]] && command -v clamscan >/dev/null 2>&1 && v="$(clamscan --version 2>/dev/null | head -1)"
  printf '%s' "$v"
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
    -X POST "${DASHBOARD_URL%/}/api/scan/report" \
    -H 'Content-Type: application/json' \
    -d "$payload" \
    > /dev/null
}

if [ -n "$INFECTED_LINES" ]; then
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
