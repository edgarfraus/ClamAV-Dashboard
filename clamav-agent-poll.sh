#!/bin/bash
#
# clamav-agent-poll.sh
#
# ClamAV Dashboard agent: asks the console whether there are scans to run on
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
#   clamav-agent-poll.sh --once     # a single pass, scan run synchronously (useful for debugging)
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

# Where the scanners actually live. ClamAV is not always on PATH: on an appliance
# - a QNAP or Synology NAS, a router distro - it ships inside an application
# package under a path nothing exports, e.g.
# /share/CACHEDEV1_DATA/.qpkg/ClamAV/bin/clamscan. Without a way to name the
# binary this script can only answer "Neither clamdscan nor clamscan is
# installed" on a machine where ClamAV is installed and working perfectly well.
# Set CLAMSCAN_BIN / CLAMDSCAN_BIN in the config file on those.
CLAMDSCAN_BIN="${CLAMDSCAN_BIN:-clamdscan}"
CLAMSCAN_BIN="${CLAMSCAN_BIN:-clamscan}"
POLL_SECONDS="${AGENT_POLL_SECONDS:-30}"
CURL_TIMEOUT="${CURL_TIMEOUT:-15}"

# What this agent can do beyond scanning, sent on every request as
# X-Agent-Capabilities. The console only hands out a command type the agent
# has declared here: an agent without "file-actions" would read a quarantine
# or restore command as a plain scan of that path.
AGENT_CAPABILITIES="file-actions"

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

# Attached to every request (X-Agent-OS) so the console knows what this
# machine actually is instead of whatever an admin happened to pick in the
# Platform dropdown - that field comes from xyz.capybara:clamav-client and
# only has UNIX/WINDOWS/JVM_PLATFORM, no macOS value, so a Mac endpoint has
# nothing better to be labelled there. This is what a full-disk scan's
# default targets are chosen from (see resolveFullDiskTargets on the
# console): /Users on macOS is not the same list as Linux's /home.
agent_os() {
  case "$(uname -s)" in
    Linux)  printf 'linux' ;;
    Darwin) printf 'macos' ;;
  esac
}

# clamd/ClamAV version on this machine, as "ClamAV 1.0.3/27263/Tue Sep  2 ...".
# Attached to every request so the console can display the signature database
# version and age without connecting to this machine, which it no longer does.
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
# The directory name quarantine_file() moves infected files into. Also the name
# in_quarantine() looks for, so the two can never drift apart.
QUARANTINE_DIRNAME=".claimav-quarantine"

# True for a detection whose file is already sitting in a quarantine directory.
#
# Scan targets are whole directory trees, and quarantine_file() has to keep the
# file on the SAME filesystem as the original (see its comment below), so the
# quarantine always lands INSIDE something that later gets scanned. Without this
# check the same file is found again by every scan, quarantined again, and
# reported to the console again - which notifies about it again, forever, for a
# threat ClamAV dealt with the first time. On Windows, where the agent uses
# clamscan's own --move, that loop is also what grows the endless
# "eicar_com.zip.001.001.001..." names.
#
# Filtered on the findings rather than with --exclude-dir because clamdscan -
# the scanner this script prefers - does not support that option at all: it
# prints "WARNING: Ignoring unsupported option --exclude-dir" and scans anyway.
in_quarantine() {
  case "$1" in
    */"$QUARANTINE_DIRNAME"/*) return 0 ;;
  esac
  return 1
}

# $2 = "keep": never fall back to deleting. A quarantine the user asked for
# from the console must leave the file restorable or fail visibly; a scan in
# Prevention mode, by contrast, prefers deletion to leaving malware in place.
quarantine_file() {
  local path="$1" keep="${2:-}" mnt="" qdir dest
  [[ -e "$path" ]] || return 1
  # POSIX "df -P", not GNU "df --output=target": macOS's df has no --output,
  # so the mount point came back empty there and every quarantine on a Mac
  # silently fell through to the rm below. Fields 6+ are the mount point,
  # which may contain spaces.
  if command -v df >/dev/null 2>&1; then
    mnt="$(df -P -- "$path" 2>/dev/null | awk 'NR==2 { for (i = 1; i <= 5; i++) $i = ""; sub(/^ +/, ""); print }')"
  fi
  if [[ -n "${mnt:-}" ]]; then
    qdir="${mnt%/}/.claimav-quarantine"
    mkdir -p "$qdir" 2>/dev/null && chmod 700 "$qdir" 2>/dev/null
    # Seconds + PID + $RANDOM, not "date +%s%N": macOS's date has no %N and
    # prints a literal N, so two files quarantined in the same second collided.
    dest="${qdir}/$(date +%s)-$$-$RANDOM-$(basename -- "$path")"
    if mv -f -- "$path" "$dest" 2>/dev/null; then
      printf 'quarantined\t%s' "$dest"
      return 0
    fi
  fi
  [[ "$keep" == "keep" ]] && return 1
  if rm -f -- "$path" 2>/dev/null; then
    printf 'removed\t'
    return 0
  fi
  return 1
}

# --fdpass hands clamd an already-open file descriptor via SCM_RIGHTS, which
# is a UNIX-domain-socket capability - TCP cannot carry it at all, at the
# kernel level, regardless of ClamAV's own configuration. A clamd reachable
# only over TCP (no LocalSocket in clamd.conf - the stock state of a
# Homebrew-installed clamd on macOS, unless someone edits the config) makes
# every --fdpass scan fail outright and identically no matter what is being
# scanned, which is exactly indistinguishable from "every target is somehow
# broken" without this check. Verified against this very script (always
# present, always readable, never infected) rather than trying to read
# clamd.conf, whose path this generic script does not always know.
fdpass_available() {
  "$CLAMDSCAN_BIN" --fdpass "$0" >/dev/null 2>&1
  [[ $? -le 1 ]]
}

# Picks whichever scanner is available. clamdscan is much faster (signatures are
# already in the daemon's memory); clamscan is the fallback when clamd is silent.
# Sets SCANNER on success. On failure, sets SCANNER_ERROR too: "neither binary
# is installed" and "clamdscan is there but clamd isn't answering" need
# completely different fixes (install the package vs. check the daemon), and
# reporting them as the same generic message sends whoever reads the job to
# the wrong place.
pick_scanner() {
  if command -v "$CLAMDSCAN_BIN" >/dev/null 2>&1; then
    if "$CLAMDSCAN_BIN" --ping 1 >/dev/null 2>&1; then
      if fdpass_available; then
        SCANNER=("$CLAMDSCAN_BIN" --multiscan --fdpass --infected)
      else
        SCANNER=("$CLAMDSCAN_BIN" --multiscan --infected)
      fi
      return 0
    elif command -v "$CLAMSCAN_BIN" >/dev/null 2>&1; then
      SCANNER=("$CLAMSCAN_BIN" --recursive --infected --exclude-dir="\\.claimav-quarantine")
      return 0
    else
      SCANNER_ERROR="clamdscan is installed but clamd is not responding to --ping"
      SCANNER_ERROR+=" (and clamscan is not installed as a fallback)."
      SCANNER_ERROR+=" Check: systemctl status 'clamd@*' clamd"
      return 1
    fi
  elif command -v "$CLAMSCAN_BIN" >/dev/null 2>&1; then
    SCANNER=("$CLAMSCAN_BIN" --recursive --infected --exclude-dir="\\.claimav-quarantine")
    return 0
  fi
  SCANNER_ERROR="Neither clamdscan nor clamscan was found ($CLAMDSCAN_BIN / $CLAMSCAN_BIN). On an appliance where ClamAV lives outside PATH, set CLAMSCAN_BIN in $CONFIG_FILE."
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
  local remediation="${6:-}" remediation_path="${7:-}" quarantined_json="${8:-}"
  local payload
  payload="{\"hostname\":\"$(json_escape "$(hostname)")\""
  payload+=",\"path\":\"$(json_escape "$target")\""
  payload+=",\"verdict\":\"${verdict}\""
  payload+=",\"commandId\":${command_id}"
  payload+=",\"findings\":${findings_json}"
  payload+=",\"errorMessage\":\"$(json_escape "$error_message")\""
  payload+=",\"remediation\":\"$(json_escape "$remediation")\""
  payload+=",\"remediationPath\":\"$(json_escape "$remediation_path")\""
  # Which original went to which quarantine path, so the console can restore
  # a single file later. Omitted when nothing was moved.
  [[ -n "$quarantined_json" ]] && payload+=",\"quarantined\":${quarantined_json}"
  payload+="}"

  if curl -sS -f --max-time "$CURL_TIMEOUT" \
       -H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}" \
       -H "X-Agent-Clamav: $(clamav_version)" \
       -H "X-Agent-OnAccess-Mode: $(current_onaccess_mode)" \
       -H "X-Agent-OS: $(agent_os)" \
       -H "X-Agent-Capabilities: ${AGENT_CAPABILITIES}" \
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

  # A scan with no signatures loaded is not a clean scan, and nothing downstream
  # would notice on its own: clamscan prints "Known viruses: 0", exits 2, and its
  # summary still reads "Infected files: 0" - which the tolerance branch further
  # down, written for individual unreadable files, then accepts as a clean
  # result. That is exactly how a Mac whose signature database was never
  # downloaded reported clean scans of /Users for days. Reported as an error
  # because that is what it is: nothing was examined.
  if printf '%s\n' "$output" | grep -qE '^Known viruses: 0$'; then
    log "command ${command_id}: NO signature database loaded - nothing was scanned"
    report_result "$command_id" "ERROR" "${existing[*]}" "[]" \
      "No signature database on this machine (Known viruses: 0): nothing was actually scanned. Run freshclam here."
    return
  fi

  # Lines shaped "<path>: <SIGNATURE> FOUND": the same format the console
  # already knows how to parse, so they are forwarded verbatim.
  local infected
  infected="$(printf '%s\n' "$output" | grep 'FOUND$' || true)"

  # Detections that are only this machine's own quarantine being scanned again:
  # see in_quarantine(). Dropped before anything else looks at them, so they are
  # neither re-quarantined, nor reported, nor notified.
  local quarantine_only=false
  if [[ -n "$infected" ]]; then
    local kept="" skipped=0
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      if in_quarantine "${line%%: *}"; then
        skipped=$((skipped + 1))
        continue
      fi
      [[ -n "$kept" ]] && kept+=$'\n'
      kept+="$line"
    done <<< "$infected"
    if [[ "$skipped" -gt 0 ]]; then
      log "command ${command_id}: ignoring ${skipped} detection(s) already in quarantine"
      [[ -z "$kept" ]] && quarantine_only=true
    fi
    infected="$kept"
  fi

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
    local quarantined_json=""
    if [[ "$desired_mode" == "prevent" ]]; then
      quarantined_json="["
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
            [[ "$quarantined_json" != "[" ]] && quarantined_json+=","
            quarantined_json+="{\"path\":\"$(json_escape "$infected_path")\",\"quarantinePath\":\"$(json_escape "$moved_path")\"}"
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
      quarantined_json+="]"
    fi
    report_result "$command_id" "VIRUS_FOUND" "${existing[*]}" "$findings_json" "" "$remediation" "$remediation_paths" "$quarantined_json"
  elif [[ "$quarantine_only" == true ]]; then
    # Everything this scan found was already in quarantine. The scanner still
    # exited 1 ("infected files found") and its summary still counts those files,
    # so without this branch neither of the two checks below would match and a
    # genuinely clean scan would be reported as an ERROR - which notifies as
    # well, just with a different icon.
    log "command ${command_id}: only files already in quarantine were found - reporting clean"
    report_result "$command_id" "OK" "${existing[*]}" "[]" ""
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

# ---------------------------------------------------------------------------
# File actions: quarantine or restore ONE file of an alert, on request from
# the console (Alerts > alert > the buttons next to each file). The console
# is trusted to ask, not to decide: every action re-checks on this machine
# that it makes sense, so a compromised or buggy console cannot use it to move
# arbitrary files around.
#   QUARANTINE    <path>            only if ClamAV still detects the file now
#   RESTORE       <qpath>\n<orig>   only out of a quarantine directory, never
#                                   over an existing file
#   RESTORE_ALLOW <qpath>\n<orig>   as RESTORE, after adding the file's SHA-256
#                                   to ClamAV's local allow list
# ---------------------------------------------------------------------------

report_action() {
  local command_id="$1" ok="$2" message="$3" qpath="${4:-}"
  local payload="{\"ok\":${ok},\"message\":\"$(json_escape "$message")\",\"quarantinePath\":\"$(json_escape "$qpath")\"}"
  if curl -sS -f --max-time "$CURL_TIMEOUT" \
       -H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}" \
       -H "X-Agent-Clamav: $(clamav_version)" \
       -H "X-Agent-OS: $(agent_os)" \
       -H "X-Agent-Capabilities: ${AGENT_CAPABILITIES}" \
       "${TLS_ARGS[@]+"${TLS_ARGS[@]}"}" \
       -X POST "${DASHBOARD_URL%/}/api/agent/commands/${command_id}/result" \
       -H 'Content-Type: application/json' \
       -d "$payload" > /dev/null; then
    log "command ${command_id}: ${message}"
  else
    log "command ${command_id}: FAILED to report the outcome (${message})"
  fi
}

file_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$1" 2>/dev/null | awk '{print $1}'
  else
    shasum -a 256 -- "$1" 2>/dev/null | awk '{print $1}'   # macOS
  fi
}

# Where ClamAV loads its databases from: the allow list has to sit next to the
# signatures to be read at all. clamd.conf's DatabaseDirectory when this
# machine's clamd.conf is known, else the usual places, the first that
# actually holds a signature database.
clamav_db_dir() {
  local d=""
  if [[ -n "${CLAMAV_DB_DIR:-}" ]]; then printf '%s' "$CLAMAV_DB_DIR"; return 0; fi
  if [[ -n "$CLAMD_CONF_PATH" && -r "$CLAMD_CONF_PATH" ]]; then
    d="$(grep -E '^[[:space:]]*DatabaseDirectory[[:space:]]+' "$CLAMD_CONF_PATH" | awk '{print $2}' | tail -1 || true)"
    [[ -n "$d" && -d "$d" ]] && { printf '%s' "$d"; return 0; }
  fi
  for d in /var/lib/clamav /opt/homebrew/var/lib/clamav /usr/local/var/lib/clamav; do
    if ls "$d"/main.c[vl]d "$d"/daily.c[vl]d "$d"/main.inc "$d"/daily.inc >/dev/null 2>&1 \
       || [[ -n "$(ls "$d"/*.c[vl]d 2>/dev/null)" ]]; then
      printf '%s' "$d"; return 0
    fi
  done
  return 1
}

# Adds a file to ClamAV's own allow list: a ".sfp" database holds
# "<sha256>:<size>:<name>" lines, and a file matching one is never reported as
# infected. Exactly that file - change one byte and it is scanned again. Then
# clamd is asked to reload, since it only reads its databases at start and on
# RELOAD (clamscan reads them on every run anyway).
ALLOW_LIST_NAME="claimav-allow.sfp"
allow_file() {
  local path="$1" label="$2" db sum size list
  db="$(clamav_db_dir)" || { ALLOW_ERROR="ClamAV database directory not found (set CLAMAV_DB_DIR in the config file)"; return 1; }
  sum="$(file_sha256 "$path")"
  size="$(wc -c < "$path" 2>/dev/null | tr -d '[:space:]')"
  if ! [[ "$sum" =~ ^[0-9a-f]{64}$ && "$size" =~ ^[0-9]+$ ]]; then
    ALLOW_ERROR="could not compute the file's SHA-256"; return 1
  fi
  list="${db%/}/${ALLOW_LIST_NAME}"
  if ! grep -qs "^${sum}:${size}:" "$list"; then
    printf '%s:%s:ClaimAV.Allowed.%s\n' "$sum" "$size" "$label" >> "$list" 2>/dev/null \
      || { ALLOW_ERROR="cannot write ${list}"; return 1; }
    chmod 644 "$list" 2>/dev/null || true
  fi
  ALLOWED_SHA256="$sum"
  "$CLAMDSCAN_BIN" --reload >/dev/null 2>&1 || true
  return 0
}

action_quarantine() {
  local command_id="$1" path="$2" out outcome
  if [[ -L "$path" || ! -f "$path" ]]; then
    report_action "$command_id" false "Not quarantined: ${path} is not a regular file on this machine any more (moved or deleted?)."
    return
  fi
  if in_quarantine "$path"; then
    report_action "$command_id" false "Not quarantined: ${path} is already inside a quarantine directory."
    return
  fi
  if ! pick_scanner; then
    report_action "$command_id" false "Not quarantined: cannot re-check the file first. ${SCANNER_ERROR}"
    return
  fi
  out="$("${SCANNER[@]}" "$path" 2>&1)"
  if ! printf '%s\n' "$out" | grep -q 'FOUND$'; then
    report_action "$command_id" false "Not quarantined: ClamAV no longer detects ${path}, so it was left where it is."
    return
  fi
  if outcome="$(quarantine_file "$path" keep)"; then
    report_action "$command_id" true "Moved into quarantine: ${outcome#*$'\t'}" "${outcome#*$'\t'}"
  else
    report_action "$command_id" false "Could not move ${path} into quarantine (no quarantine directory on its filesystem, or not permitted). Nothing was changed."
  fi
}

action_restore() {
  local command_id="$1" allow="$2" qpath="$3" orig="$4" parent note=""
  if ! in_quarantine "$qpath"; then
    report_action "$command_id" false "Not restored: ${qpath} is not inside a quarantine directory."
    return
  fi
  if [[ -L "$qpath" || ! -f "$qpath" ]]; then
    report_action "$command_id" false "Not restored: ${qpath} is no longer in quarantine (already restored or removed?)."
    return
  fi
  if [[ "$orig" != /* ]] || in_quarantine "$orig"; then
    report_action "$command_id" false "Not restored: invalid destination ${orig}."
    return
  fi
  if [[ -e "$orig" || -L "$orig" ]]; then
    report_action "$command_id" false "Not restored: a file already exists at ${orig}, and it is never overwritten."
    return
  fi
  parent="$(dirname -- "$orig")"
  if [[ ! -d "$parent" ]]; then
    report_action "$command_id" false "Not restored: the folder ${parent} no longer exists."
    return
  fi
  if [[ "$allow" == true ]]; then
    ALLOW_ERROR=""; ALLOWED_SHA256=""
    if ! allow_file "$qpath" "$command_id"; then
      report_action "$command_id" false "Not restored: could not add the file to the allow list (${ALLOW_ERROR}). Nothing was changed."
      return
    fi
    note=" and allow-listed (SHA-256 ${ALLOWED_SHA256})"
  fi
  if mv -- "$qpath" "$orig" 2>/dev/null; then
    report_action "$command_id" true "Restored to ${orig}${note}."
  else
    report_action "$command_id" false "Could not move the file back to ${orig}${note:+ (it was allow-listed nonetheless)}."
  fi
}

run_file_action() {
  local command_id="$1" type="$2" target="$3"
  case "$type" in
    QUARANTINE)    action_quarantine "$command_id" "$target" ;;
    RESTORE)       action_restore "$command_id" false "${target%%$'\n'*}" "${target#*$'\n'}" ;;
    RESTORE_ALLOW) action_restore "$command_id" true  "${target%%$'\n'*}" "${target#*$'\n'}" ;;
  esac
}

poll_once() {
  local background="${1:-false}"
  local response
  response="$(curl -sS -f --max-time "$CURL_TIMEOUT" \
      -H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}" \
      -H "X-Agent-Clamav: $(clamav_version)" \
      -H "X-Agent-OnAccess-Mode: $(current_onaccess_mode)" \
      -H "X-Agent-OS: $(agent_os)" \
      -H "X-Agent-Capabilities: ${AGENT_CAPABILITIES}" \
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
  # A third field marks a file action ("<id> <base64> QUARANTINE"); scans
  # have none.
  local desired_mode=""
  while IFS=' ' read -r command_id encoded command_type; do
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
    case "${command_type:-SCAN}" in
      SCAN) ;;
      QUARANTINE|RESTORE|RESTORE_ALLOW)
        # Quick (a single file), but a quarantine re-scans the file first,
        # which with clamscan as the fallback means loading every signature:
        # backgrounded in the loop for the same reason as a scan.
        log "command ${command_id}: ${command_type}"
        if [[ "$background" == true ]]; then
          run_file_action "$command_id" "$command_type" "$targets_raw" &
        else
          run_file_action "$command_id" "$command_type" "$targets_raw"
        fi
        continue
        ;;
      *)
        log "command ${command_id}: unknown command type '${command_type}', skipping"
        continue
        ;;
    esac
    if [[ "$background" == true ]]; then
      # Backgrounded so this loop goes straight back to sleep and polls again
      # on schedule instead of blocking on the scan. It's the GET above that
      # updates "last seen" on the console (AgentAuthenticationFilter.touchAgentSeen);
      # a full-disk scan can easily run past AGENT_ALIVE_WINDOW (15 min, see
      # ApiController), and without this a perfectly healthy agent that is
      # actively scanning would get flagged offline for as long as it takes.
      # Safe to fire and forget: GET /api/agent/commands claims a command
      # (QUEUED -> DISPATCHED) the moment it's returned, so the next poll
      # never re-picks the one already running here.
      log "command ${command_id}: scanning in the background (poll loop keeps sending heartbeats)"
      run_command "$command_id" "$targets_raw" "$desired_mode" &
    else
      run_command "$command_id" "$targets_raw" "$desired_mode"
    fi
  done <<< "$response"
}

case "${1:---loop}" in
  --once)
    poll_once false
    ;;
  --loop)
    log "agent started: console ${DASHBOARD_URL%/}, polling every ${POLL_SECONDS}s"
    while true; do
      poll_once true
      sleep "$POLL_SECONDS"
    done
    ;;
  *)
    awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
    exit 1
    ;;
esac
