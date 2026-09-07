#!/bin/bash
#
# clamav-onacc-report.sh
#
# Forwards every detection made by ClamAV's on-access scanner (clamonacc) to the
# ClaimAV console in real time, via POST /api/scan/report.
#
# Why it follows a log instead of using clamd's VirusEvent:
#   VirusEvent does NOT fire for on-access scans. It has been deliberately
#   disabled in ClamAV since 0.100 (virusaction() is commented out in
#   onaccess_fan.c: "virusaction forks. This could be extraordinarily
#   problematic, lead to deadlocks..."), and it does not fire for
#   clamdscan --fdpass either. The only place the REAL PATH of the infected
#   file appears is clamonacc's own output, which logs:
#
#       <path>: <SIGNATURE> FOUND
#
#   (clamonacc/client/protocol.c: logg("%s%s FOUND", display_filename, colon)
#   for scantype >= STREAM, so with both --fdpass and --stream).
#   With LogTime enabled the line is prefixed with "Thu Sep  7 14:23:01 2026 -> ".
#
# USAGE:
#   clamav-onacc-report.sh --follow /var/log/clamav/clamonacc.log   # service
#   clamav-onacc-report.sh --test                                    # check the connection
#
# CONFIGURATION: /etc/clamav/console-report.conf (chmod 600):
#   DASHBOARD_URL='https://console.example.com'
#   DASHBOARD_AGENT_KEY='cav_...'
# or, alternatively (legacy, dedicated OPERATOR user):
#   DASHBOARD_API_USER='agent-host'
#   DASHBOARD_API_PASSWORD='...'
#
# Optional:
#   DASHBOARD_CA_BUNDLE='/etc/ssl/certs/internal-ca.pem'   # private CA
#   DASHBOARD_INSECURE=1                                    # skip TLS verification
#
set -uo pipefail

CONFIG_FILE="${CONFIG_FILE:-/etc/clamav/console-report.conf}"
SPOOL_DIR="${SPOOL_DIR:-/var/lib/clamav-console-report/spool}"
DEDUP_DIR="${DEDUP_DIR:-/var/lib/clamav-console-report/dedup}"
# The same path+signature pair is not re-sent more than once per this many
# seconds: an infected file opened in a loop would otherwise produce a burst
# of identical notifications.
DEDUP_SECONDS="${DEDUP_SECONDS:-300}"
CURL_TIMEOUT="${CURL_TIMEOUT:-10}"

[[ -r "$CONFIG_FILE" ]] && . "$CONFIG_FILE"

DASHBOARD_URL="${DASHBOARD_URL:-}"
DASHBOARD_AGENT_KEY="${DASHBOARD_AGENT_KEY:-}"
DASHBOARD_API_USER="${DASHBOARD_API_USER:-}"
DASHBOARD_API_PASSWORD="${DASHBOARD_API_PASSWORD:-}"
DASHBOARD_CA_BUNDLE="${DASHBOARD_CA_BUNDLE:-}"
DASHBOARD_INSECURE="${DASHBOARD_INSECURE:-0}"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >&2; }

# The endpoint key is the preferred credential: it is valid for this endpoint
# only. The OPERATOR user is still supported for installs predating keys.
if [[ -n "$DASHBOARD_AGENT_KEY" ]]; then
  AUTH_ARGS=(-H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}")
elif [[ -n "$DASHBOARD_API_USER" && -n "$DASHBOARD_API_PASSWORD" ]]; then
  AUTH_ARGS=(-u "${DASHBOARD_API_USER}:${DASHBOARD_API_PASSWORD}")
else
  AUTH_ARGS=()
fi

# TLS options, for a console behind a reverse proxy with a private CA.
TLS_ARGS=()
[[ -n "$DASHBOARD_CA_BUNDLE" ]] && TLS_ARGS+=(--cacert "$DASHBOARD_CA_BUNDLE")
[[ "$DASHBOARD_INSECURE" == "1" ]] && TLS_ARGS+=(--insecure)

if [[ -z "$DASHBOARD_URL" || ${#AUTH_ARGS[@]} -eq 0 ]]; then
  log "ERROR: incomplete configuration. DASHBOARD_URL and DASHBOARD_AGENT_KEY"
  log "       (or DASHBOARD_API_USER + DASHBOARD_API_PASSWORD) are required in $CONFIG_FILE."
  exit 1
fi

mkdir -p "$SPOOL_DIR" "$DEDUP_DIR" 2>/dev/null || true

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\t'/\\t}"
  s="${s//$'\r'/\\r}"
  s="${s//$'\n'/\\n}"
  printf '%s' "$s"
}

# clamd/ClamAV version on this machine, formatted as
# "ClamAV 1.0.3/27263/Tue Sep  2 ...". Attached to every request so the console
# can show the signature database version and age without connecting here.
clamav_version() {
  local v=""
  if command -v clamdscan >/dev/null 2>&1; then
    v="$(clamdscan --version 2>/dev/null | head -1)"
  fi
  if [[ -z "$v" ]] && command -v clamscan >/dev/null 2>&1; then
    v="$(clamscan --version 2>/dev/null | head -1)"
  fi
  printf '%s' "$v"
}

# POSTs the payload. If the console does not answer, the payload is spooled and
# retried on the next event: malware found while the console is down must not
# vanish silently.
post_payload() {
  local payload="$1"
  curl -sS -f --max-time "$CURL_TIMEOUT" \
    "${AUTH_ARGS[@]}" "${TLS_ARGS[@]+"${TLS_ARGS[@]}"}" \
    -H "X-Agent-Clamav: $(clamav_version)" \
    -X POST "${DASHBOARD_URL%/}/api/scan/report" \
    -H 'Content-Type: application/json' \
    -d "$payload" > /dev/null 2>&1
}

spool_payload() {
  local payload="$1"
  local f
  f="${SPOOL_DIR}/$(date +%s%N).json"
  printf '%s' "$payload" > "$f" 2>/dev/null && chmod 600 "$f" 2>/dev/null
  log "console unreachable: event queued ($f)"
}

flush_spool() {
  local f
  shopt -s nullglob
  for f in "$SPOOL_DIR"/*.json; do
    if post_payload "$(cat "$f")"; then
      rm -f "$f"
      log "queued event sent ($(basename "$f"))"
    else
      break   # console still down: retry on the next event
    fi
  done
  shopt -u nullglob
}

send_finding() {
  local path="$1" sig="$2"
  local payload
  payload=$(printf '{"hostname":"%s","path":"%s","verdict":"VIRUS_FOUND","source":"realtime","findings":["%s"]}' \
    "$(json_escape "$(hostname)")" \
    "$(json_escape "$path")" \
    "$(json_escape "${path}: ${sig} FOUND")")

  flush_spool
  if post_payload "$payload"; then
    log "sent: $path ($sig)"
  else
    spool_payload "$payload"
  fi
}

# Extracts path and signature from a clamonacc line, then applies deduplication.
process_line() {
  local line="$1"

  # Strip ClamAV's timestamp prefix ("... -> "), when present.
  line="${line##* -> }"
  [[ "$line" == *" FOUND" ]] || return 0

  local body="${line% FOUND}"      # "<path>: <SIGNATURE>"
  [[ "$body" == *": "* ]] || return 0
  local path="${body%: *}"         # the path may contain ": ", the signature may not
  local sig="${body##*: }"
  [[ -n "$path" && -n "$sig" ]] || return 0

  local now key marker last
  now=$(date +%s)
  # File-based deduplication rather than an associative array: works on bash 3.x
  # too, and survives a service restart (no burst of duplicates if the reporter
  # restarts while the infected file is still there).
  key=$(printf '%s' "${path}|${sig}" | cksum | cut -d' ' -f1)
  marker="${DEDUP_DIR}/${key}"
  if [[ -f "$marker" ]]; then
    last=$(cat "$marker" 2>/dev/null || echo 0)
    [[ "$last" =~ ^[0-9]+$ ]] || last=0
    if (( now - last < DEDUP_SECONDS )); then
      return 0
    fi
  fi
  printf '%s' "$now" > "$marker" 2>/dev/null
  # Old markers are useless: prune them now and then so they do not pile up.
  find "$DEDUP_DIR" -type f -mmin +120 -delete 2>/dev/null || true

  send_finding "$path" "$sig"
}

# Reports exactly why the console could not be reached. Hiding curl's error
# behind "-s" is what makes these failures impossible to diagnose.
diagnose_console() {
  local url="${DASHBOARD_URL%/}/api/agent/commands?format=text"
  local errfile http rc
  errfile=$(mktemp)
  http=$(curl -sS -o /dev/null -w '%{http_code}' --max-time "$CURL_TIMEOUT" \
         "${AUTH_ARGS[@]}" "${TLS_ARGS[@]+"${TLS_ARGS[@]}"}" "$url" 2>"$errfile")
  rc=$?

  echo "Console : ${DASHBOARD_URL}"
  echo "Endpoint: /api/agent/commands"
  echo "curl exit=${rc} http=${http}"
  if [[ -s "$errfile" ]]; then
    echo "curl says: $(tr -d '\r' < "$errfile" | tail -2 | tr '\n' ' ')"
  fi
  rm -f "$errfile"

  case "$rc" in
    0) : ;;
    6)  echo "-> DNS: the console hostname does not resolve from this machine." ;;
    7)  echo "-> Connection refused: wrong port, or the reverse proxy is not listening." ;;
    28) echo "-> Timeout: a firewall is likely dropping the traffic." ;;
    35|60)
        echo "-> TLS: the certificate was not accepted. With a private CA, set"
        echo "   DASHBOARD_CA_BUNDLE=/path/to/ca.pem in $CONFIG_FILE."
        echo "   To confirm that this is the cause (do not leave it on):"
        echo "     DASHBOARD_INSECURE=1 $0 --test" ;;
    *)  echo "-> See 'man curl' for exit code ${rc}." ;;
  esac

  case "$http" in
    200) echo "-> The console accepted the key. The connection works." ;;
    401|403) echo "-> The key was rejected. It may have been rotated in the console (Admin > Endpoints)." ;;
    404) echo "-> 404: the URL does not reach the console. Check the reverse proxy path." ;;
    502|503|504) echo "-> The reverse proxy cannot reach the console behind it." ;;
  esac

  [[ "$rc" == "0" && "$http" == "200" ]]
}

case "${1:-}" in
  --test)
    if diagnose_console; then
      echo
      echo "OK: this machine can talk to the console."
      exit 0
    else
      echo
      echo "FAILED: this machine cannot talk to the console (details above)."
      exit 1
    fi
    ;;
  --follow)
    LOGFILE="${2:-/var/log/clamav/clamonacc.log}"
    log "following $LOGFILE, forwarding to ${DASHBOARD_URL%/}/api/scan/report"
    # -n0: new events only. -F: survives log rotation.
    tail -n0 -F "$LOGFILE" 2>/dev/null | while IFS= read -r line; do
      process_line "$line"
    done
    ;;
  *)
    awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
    exit 1
    ;;
esac
