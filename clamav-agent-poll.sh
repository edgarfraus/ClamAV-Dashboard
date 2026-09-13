#!/bin/bash
#
# clamav-agent-poll.sh
#
# ClaimAV Dashboard agent: asks the console whether there are scans to run on
# this machine, runs them locally and reports the outcome.
#
# Why polling instead of listening: the console must not need to reach this
# machine. Nothing has to be exposed, it works behind NAT, and the only
# credential involved is the endpoint key.
#
# Why an agent instead of PATH scans over TCP: here clamdscan runs on the
# machine and passes already-open file descriptors (--fdpass), so none of the
# permission/SELinux problems apply that make scans fail when clamd, reached
# remotely, has to open the files itself.
#
# USAGE:
#   clamav-agent-poll.sh --loop     # service: keeps polling the console
#   clamav-agent-poll.sh --once     # a single pass (useful for debugging)
#
# CONFIGURATION: /etc/clamav/console-report.conf (chmod 600)
#   DASHBOARD_URL='https://console.example.com'
#   DASHBOARD_AGENT_KEY='cav_...'
#   AGENT_POLL_SECONDS=30                                 # optional
#   DASHBOARD_CA_BUNDLE='/etc/ssl/certs/internal-ca.pem'  # optional, private CA
#   DASHBOARD_INSECURE=1                                  # optional, skip TLS verification
#
set -uo pipefail

# On macOS (install_macos() in install.sh.tpl reuses this same script under a
# LaunchDaemon) this runs with launchd's own minimal PATH, not the interactive
# shell PATH that "brew shellenv" sets up - so clamscan/clamdscan, installed
# under Homebrew's prefix, are invisible to "command -v" here even though
# they work fine from a Terminal. Without this, every scan fails with
# "Neither clamdscan nor clamscan is installed on this machine." on a machine
# where both plainly are. No-op on Linux, where these paths don't exist.
export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/local/sbin:$PATH"

CONFIG_FILE="${CONFIG_FILE:-/etc/clamav/console-report.conf}"
[[ -r "$CONFIG_FILE" ]] && . "$CONFIG_FILE"

# Written by install-clamd-remote.sh only when --on-access was used: where
# clamd's own config lives and which systemd unit runs it, so the on-access
# mode the console asks for (Admin > Groups > realtime mode) can be applied
# here without re-detecting the distro layout on every poll. Absent on a
# machine without realtime installed - every function below treats that as
# "nothing to sync" rather than an error.
ONACCESS_FACTS="${ONACCESS_FACTS:-/etc/clamav/onaccess.conf}"
[[ -r "$ONACCESS_FACTS" ]] && . "$ONACCESS_FACTS"
CLAMD_CONF_PATH="${CLAMD_CONF_PATH:-}"
CLAMD_SERVICE_UNIT="${CLAMD_SERVICE_UNIT:-}"

DASHBOARD_URL="${DASHBOARD_URL:-}"
DASHBOARD_AGENT_KEY="${DASHBOARD_AGENT_KEY:-}"
DASHBOARD_CA_BUNDLE="${DASHBOARD_CA_BUNDLE:-}"
DASHBOARD_INSECURE="${DASHBOARD_INSECURE:-0}"
POLL_SECONDS="${AGENT_POLL_SECONDS:-30}"
CURL_TIMEOUT="${CURL_TIMEOUT:-15}"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >&2; }

if [[ -z "$DASHBOARD_URL" || -z "$DASHBOARD_AGENT_KEY" ]]; then
  log "ERROR: DASHBOARD_URL and DASHBOARD_AGENT_KEY are required in $CONFIG_FILE."
  exit 1
fi

TLS_ARGS=()
[[ -n "$DASHBOARD_CA_BUNDLE" ]] && TLS_ARGS+=(--cacert "$DASHBOARD_CA_BUNDLE")
[[ "$DASHBOARD_INSECURE" == "1" ]] && TLS_ARGS+=(--insecure)

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\t'/\\t}"
  s="${s//$'\r'/\\r}"
  s="${s//$'\n'/\\n}"
  printf '%s' "$s"
}

# clamd/ClamAV version on this machine, as "ClamAV 1.0.3/27263/Tue Sep  2 ...".
# Attached to every request so the console can display the signature database
# version and age without connecting to this machine, which it no longer does.
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

# Reads OnAccessPrevention from THIS machine's own clamd.conf, when the
# on-access installer left CLAMD_CONF_PATH behind. Attached to every request
# (X-Agent-OnAccess-Mode) so the console shows what is REALLY in effect, not
# just the mode it last asked for.
current_onaccess_mode() {
  # This script also runs unmodified on macOS (install_macos() in
  # install.sh.tpl reuses it verbatim): on-access uses fanotify, which is
  # Linux-only, so realtime can never exist there, not just "not installed
  # yet" - report that plainly instead of an empty value a console can't
  # tell apart from "hasn't reported yet".
  if [[ "$(uname -s)" != "Linux" ]]; then
    printf 'unsupported'
    return 0
  fi
  [[ -n "$CLAMD_CONF_PATH" && -r "$CLAMD_CONF_PATH" ]] || return 0
  local v
  v="$(grep -E '^[[:space:]]*OnAccessPrevention[[:space:]]+' "$CLAMD_CONF_PATH" | awk '{print $2}' | tail -1 || true)"
  case "$v" in
    yes) printf 'prevent' ;;
    no)  printf 'detect' ;;
  esac
}

# Applies the mode the console asked for (Admin > Groups > realtime mode) to
# this machine, when realtime is actually installed here. A restart is
# required, not just the config edit: OnAccessPrevention decides which class
# of fanotify event clamd requests (blocking vs notification), so it only
# takes effect from clamd's next start.
# Below this many seconds since the last actual switch, do nothing even if
# asked again: a machine this poll loop runs on every 30s, so without a floor
# any condition that kept "current" from ever reading back as "desired" (an
# unparseable clamd.conf line, a restart that silently didn't take) would
# restart clamd and clamonacc every single cycle forever - and clamd reloading
# its full signature set on every start is exactly the kind of load that can
# make the agent itself time out and look offline. This is a backstop on top
# of the "current == desired" check below, not a replacement for it.
ONACCESS_SYNC_COOLDOWN="${ONACCESS_SYNC_COOLDOWN:-300}"
ONACCESS_SYNC_MARKER="/var/lib/clamav-console-report/onaccess-last-sync"

sync_onaccess_mode() {
  local desired="$1"
  [[ "$desired" == "prevent" || "$desired" == "detect" ]] || return 0
  [[ -n "$CLAMD_CONF_PATH" && -w "$CLAMD_CONF_PATH" && -n "$CLAMD_SERVICE_UNIT" ]] || return 0
  command -v systemctl >/dev/null 2>&1 || return 0
  systemctl cat clamav-onacc.service >/dev/null 2>&1 || return 0

  local current
  current="$(current_onaccess_mode)"
  # Empty means OnAccessPrevention could not be read back at all (missing
  # line, unexpected format in that machine's clamd.conf). Rewriting blind
  # here on every cycle, unable to ever confirm convergence, is exactly the
  # thrashing risk described above - skip and let an admin look, instead.
  if [[ -z "$current" ]]; then
    log "on-access mode: could not read OnAccessPrevention from $CLAMD_CONF_PATH, skipping sync"
    return 0
  fi
  [[ "$current" == "$desired" ]] && return 0

  local now last
  now=$(date +%s)
  last=$(cat "$ONACCESS_SYNC_MARKER" 2>/dev/null || echo 0)
  [[ "$last" =~ ^[0-9]+$ ]] || last=0
  if (( now - last < ONACCESS_SYNC_COOLDOWN )); then
    log "on-access mode: '${current}' still differs from '${desired}' but last synced $((now - last))s ago, waiting out the cooldown"
    return 0
  fi
  mkdir -p "$(dirname "$ONACCESS_SYNC_MARKER")" 2>/dev/null
  printf '%s' "$now" > "$ONACCESS_SYNC_MARKER" 2>/dev/null

  local value="no"
  [[ "$desired" == "prevent" ]] && value="yes"
  if grep -qE '^[[:space:]]*OnAccessPrevention[[:space:]]+' "$CLAMD_CONF_PATH"; then
    sed -i -E "s|^[#[:space:]]*OnAccessPrevention[[:space:]].*|OnAccessPrevention ${value}|" "$CLAMD_CONF_PATH"
  else
    echo "OnAccessPrevention ${value}" >> "$CLAMD_CONF_PATH"
  fi

  log "on-access mode: switching '${current}' -> '${desired}' (restarting clamd + clamonacc)"
  systemctl restart "$CLAMD_SERVICE_UNIT" 2>&1 | while IFS= read -r l; do log "  $l"; done
  systemctl restart clamav-onacc.service 2>&1 | while IFS= read -r l; do log "  $l"; done
}

# Moves an infected file into quarantine, run by this script itself (as root)
# rather than via clamdscan/clamscan's own --move: that flag is carried out by
# clamd, which needs ITS OWN configured user (root or the packaged "clamav"
# user, depending on install-clamd-remote.sh's choices) to have write access
# to both the file and the destination - one more unknown this sidesteps.
#
# The quarantine directory is created on the SAME filesystem as the infected
# file (via "df"), not a fixed path: a same-filesystem "mv" is a pure
# rename() that never opens/reads the file's content, so - unlike a
# cross-filesystem move, which copies the bytes first - it is never itself
# intercepted by an OnAccessPrevention=yes block on a file already known to
# be infected (this matters when clamd runs as its non-root packaged user for
# fd-passing: root is not automatically excluded from on-access there the way
# it is when clamd runs as root). If the move still fails, or "df" is
# unavailable, falling back to unlink() (rm) keeps the same guarantee -
# deleting is metadata-only too - and getting the file off disk matters more
# here than preserving a copy for forensics.
quarantine_file() {
  local path="$1" mnt qdir dest
  [[ -e "$path" ]] || return 1
  if command -v df >/dev/null 2>&1; then
    mnt="$(df --output=target "$path" 2>/dev/null | tail -1)"
  fi
  if [[ -n "${mnt:-}" ]]; then
    qdir="${mnt%/}/.claimav-quarantine"
    mkdir -p "$qdir" 2>/dev/null && chmod 700 "$qdir" 2>/dev/null
    dest="${qdir}/$(date +%s%N)-$(basename -- "$path")"
    if mv -f -- "$path" "$dest" 2>/dev/null; then
      printf 'quarantined\t%s' "$dest"
      return 0
    fi
  fi
  if rm -f -- "$path" 2>/dev/null; then
    printf 'removed\t'
    return 0
  fi
  return 1
}

# Picks whichever scanner is available. clamdscan is much faster (signatures are
# already in the daemon's memory); clamscan is the fallback when clamd is silent.
# Sets SCANNER on success. On failure, sets SCANNER_ERROR too: "neither binary
# is installed" and "clamdscan is there but clamd isn't answering" need
# completely different fixes (install the package vs. check the daemon), and
# reporting them as the same generic message sends whoever reads the job to
# the wrong place.
pick_scanner() {
  if command -v clamdscan >/dev/null 2>&1; then
    if clamdscan --ping 1 >/dev/null 2>&1; then
      SCANNER=(clamdscan --multiscan --fdpass --infected)
      return 0
    elif command -v clamscan >/dev/null 2>&1; then
      SCANNER=(clamscan --recursive --infected)
      return 0
    else
      SCANNER_ERROR="clamdscan is installed but clamd is not responding to --ping"
      SCANNER_ERROR+=" (and clamscan is not installed as a fallback)."
      SCANNER_ERROR+=" Check: systemctl status 'clamd@*' clamd"
      return 1
    fi
  elif command -v clamscan >/dev/null 2>&1; then
    SCANNER=(clamscan --recursive --infected)
    return 0
  fi
  SCANNER_ERROR="Neither clamdscan nor clamscan is installed on this machine."
  return 1
}

# clamdscan prints the real error first and a summary afterwards, so a blind
# "tail" returns "Infected files: 0 / Total errors: 1" and hides the cause.
# Prefer the actual error lines.
scan_error_detail() {
  local out="$1" detail
  detail="$(printf '%s\n' "$out" | grep -iE "^ERROR:|Can't access|Permission denied|lstat\(\) failed|No such file" | head -3)"
  [[ -z "$detail" ]] && detail="$(printf '%s\n' "$out" | tail -3)"
  printf '%s' "$detail"
}

report_result() {
  local command_id="$1" verdict="$2" target="$3" findings_json="$4" error_message="$5"
  local remediation="${6:-}" remediation_path="${7:-}"
  local payload
  payload="{\"hostname\":\"$(json_escape "$(hostname)")\""
  payload+=",\"path\":\"$(json_escape "$target")\""
  payload+=",\"verdict\":\"${verdict}\""
  payload+=",\"commandId\":${command_id}"
  payload+=",\"findings\":${findings_json}"
  payload+=",\"errorMessage\":\"$(json_escape "$error_message")\""
  payload+=",\"remediation\":\"$(json_escape "$remediation")\""
  payload+=",\"remediationPath\":\"$(json_escape "$remediation_path")\"}"

  if curl -sS -f --max-time "$CURL_TIMEOUT" \
       -H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}" \
       -H "X-Agent-Clamav: $(clamav_version)" \
       -H "X-Agent-OnAccess-Mode: $(current_onaccess_mode)" \
       "${TLS_ARGS[@]+"${TLS_ARGS[@]}"}" \
       -X POST "${DASHBOARD_URL%/}/api/scan/report" \
       -H 'Content-Type: application/json' \
       -d "$payload" > /dev/null; then
    log "command ${command_id}: ${verdict} reported"
  else
    log "command ${command_id}: FAILED to report the outcome (the console will time it out)"
  fi
}

run_command() {
  local command_id="$1" targets_raw="$2" desired_mode="${3:-}"

  # Targets arrive one per line; collect them into an array so they can all be
  # passed to a single scanner invocation.
  local targets=()
  while IFS= read -r line; do
    [[ -n "$line" ]] && targets+=("$line")
  done <<< "$targets_raw"

  if [[ ${#targets[@]} -eq 0 ]]; then
    report_result "$command_id" "ERROR" "$targets_raw" "[]" "Command carried no paths to scan."
    return
  fi

  # Drop targets that don't exist on THIS machine before handing them to the
  # scanner: clamdscan/clamscan given several paths at once return a nonzero
  # exit code as soon as ANY of them is missing, which would otherwise mark an
  # entire full-disk/custom-target scan as ERROR even though every real
  # directory scanned clean (full disk targets and scheduled scan paths are
  # configured once for a whole fleet, so not every machine has every path).
  local existing=() missing=()
  for t in "${targets[@]}"; do
    if [[ -e "$t" ]]; then existing+=("$t"); else missing+=("$t"); fi
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    log "command ${command_id}: skipping missing target(s): ${missing[*]}"
  fi
  if [[ ${#existing[@]} -eq 0 ]]; then
    report_result "$command_id" "ERROR" "${targets[*]}" "[]" \
      "None of the requested paths exist on this machine: ${targets[*]}"
    return
  fi

  if ! pick_scanner; then
    report_result "$command_id" "ERROR" "${existing[*]}" "[]" "$SCANNER_ERROR"
    return
  fi

  log "command ${command_id}: scanning ${existing[*]}"
  local output exit_code
  output="$("${SCANNER[@]}" "${existing[@]}" 2>&1)"
  exit_code=$?

  # Lines shaped "<path>: <SIGNATURE> FOUND": the same format the console
  # already knows how to parse, so they are forwarded verbatim.
  local infected
  infected="$(printf '%s\n' "$output" | grep 'FOUND$' || true)"

  local findings_json="[]"
  if [[ -n "$infected" ]]; then
    findings_json="["
    local first=true
    while IFS= read -r line; do
      if [[ "$first" == true ]]; then first=false; else findings_json+=","; fi
      findings_json+="\"$(json_escape "$line")\""
    done <<< "$infected"
    findings_json+="]"
  fi

  if [[ -n "$infected" ]]; then
    # Prevention: quarantine every infected file this scan just found. Detection
    # (or a group that never set a mode - desired_mode empty) reports only, as
    # documented: nothing here is touched.
    local remediation="not_attempted" remediation_paths="" any_failed=false any_quarantined=false any_removed=false
    if [[ "$desired_mode" == "prevent" ]]; then
      while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        local infected_path="${line%%: *}"
        local outcome action moved_path
        if outcome="$(quarantine_file "$infected_path")"; then
          action="${outcome%%$'\t'*}"
          moved_path="${outcome#*$'\t'}"
          if [[ "$action" == "quarantined" && -n "$moved_path" ]]; then
            any_quarantined=true
            [[ -n "$remediation_paths" ]] && remediation_paths+="; "
            remediation_paths+="$moved_path"
          else
            any_removed=true
          fi
        else
          any_failed=true
          log "command ${command_id}: could not quarantine or remove ${infected_path}"
        fi
      done <<< "$infected"
      if [[ "$any_failed" == true ]]; then
        remediation="failed"
      elif [[ "$any_quarantined" == true ]]; then
        remediation="quarantined"
      elif [[ "$any_removed" == true ]]; then
        remediation="removed"
      fi
    fi
    report_result "$command_id" "VIRUS_FOUND" "${existing[*]}" "$findings_json" "" "$remediation" "$remediation_paths"
  elif [[ "$exit_code" -eq 0 ]]; then
    # No virus: a clean result still has to be reported, because somebody
    # started this scan from the console and is waiting for the answer.
    report_result "$command_id" "OK" "${existing[*]}" "[]" ""
  elif printf '%s\n' "$output" | grep -qE '^Infected files: 0$'; then
    # clamdscan/clamscan exit 2 for two very different situations it cannot
    # tell apart in its own exit code: the scan never really ran (bad target,
    # daemon died mid-scan...) and "it ran to completion, found nothing, but
    # could not open some individual file along the way" (a permission-
    # protected system file, a socket, a SIP-restricted path on macOS...).
    # The summary line only gets printed once a scan has actually finished,
    # so its presence with zero infections is solid evidence of the second
    # case - completely ordinary noise on a broad target like a full-disk
    # scan, on every platform, not a reason to mark a clean result as failed.
    log "command ${command_id}: clean, but some paths could not be opened: $(scan_error_detail "$output")"
    report_result "$command_id" "OK" "${existing[*]}" "[]" ""
  else
    report_result "$command_id" "ERROR" "${existing[*]}" "[]" \
      "Scan failed (exit code ${exit_code}): $(scan_error_detail "$output")"
  fi
}

poll_once() {
  local response
  response="$(curl -sS -f --max-time "$CURL_TIMEOUT" \
      -H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}" \
      -H "X-Agent-Clamav: $(clamav_version)" \
      -H "X-Agent-OnAccess-Mode: $(current_onaccess_mode)" \
      "${TLS_ARGS[@]+"${TLS_ARGS[@]}"}" \
      "${DASHBOARD_URL%/}/api/agent/commands?format=text" 2>/dev/null)" || {
    log "console unreachable, retrying on the next pass"
    return 0
  }

  [[ -z "$response" ]] && return 0

  # Each line is either "MODE detect|prevent" (the console's desired on-access
  # mode for this endpoint's group, at most one such line, always first) or
  # "<id> <base64-encoded targets>" (a scan command). "MODE" is never a valid
  # command id (those are numeric), so the two never collide. The mode also
  # governs remediation for scans run from here on: Prevention quarantines
  # what it finds, Detection only ever reports.
  local desired_mode=""
  while IFS=' ' read -r command_id encoded; do
    [[ -z "$command_id" || -z "$encoded" ]] && continue
    if [[ "$command_id" == "MODE" ]]; then
      desired_mode="$encoded"
      sync_onaccess_mode "$encoded"
      continue
    fi
    local targets_raw
    targets_raw="$(printf '%s' "$encoded" | base64 -d 2>/dev/null)" || {
      log "command ${command_id}: undecodable targets, skipping"
      continue
    }
    run_command "$command_id" "$targets_raw" "$desired_mode"
  done <<< "$response"
}

case "${1:---loop}" in
  --once)
    poll_once
    ;;
  --loop)
    log "agent started: console ${DASHBOARD_URL%/}, polling every ${POLL_SECONDS}s"
    while true; do
      poll_once
      sleep "$POLL_SECONDS"
    done
    ;;
  *)
    awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
    exit 1
    ;;
esac
