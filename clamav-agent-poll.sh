#!/bin/bash
#
# clamav-agent-poll.sh
#
# Agent della ClaimAV Dashboard: chiede alla console se ci sono scansioni da
# fare su questa macchina, le esegue in locale e ne riporta l'esito.
#
# Perche' in polling e non in ascolto: la console non deve poter raggiungere la
# macchina. Cosi' non si apre nessuna porta, funziona dietro NAT e l'unica
# credenziale in gioco e' la chiave dell'endpoint.
#
# Perche' l'agent invece delle scansioni PATH via TCP: qui clamdscan gira sulla
# macchina e passa i descrittori di file gia' aperti (--fdpass), quindi non
# esistono i problemi di permessi/SELinux che fanno fallire le scansioni in cui
# e' clamd, da remoto, a dover aprire i file.
#
# USO:
#   clamav-agent-poll.sh --loop     # servizio: interroga la console di continuo
#   clamav-agent-poll.sh --once     # un solo giro (utile per il debug)
#
# CONFIGURAZIONE: /etc/clamav/console-report.conf (chmod 600)
#   DASHBOARD_URL='http://192.168.1.50:8080'
#   DASHBOARD_AGENT_KEY='cav_...'
#   AGENT_POLL_SECONDS=30          # opzionale
#
set -uo pipefail

CONFIG_FILE="${CONFIG_FILE:-/etc/clamav/console-report.conf}"
[[ -r "$CONFIG_FILE" ]] && . "$CONFIG_FILE"

DASHBOARD_URL="${DASHBOARD_URL:-}"
DASHBOARD_AGENT_KEY="${DASHBOARD_AGENT_KEY:-}"
POLL_SECONDS="${AGENT_POLL_SECONDS:-30}"
CURL_TIMEOUT="${CURL_TIMEOUT:-15}"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >&2; }

if [[ -z "$DASHBOARD_URL" || -z "$DASHBOARD_AGENT_KEY" ]]; then
  log "ERRORE: servono DASHBOARD_URL e DASHBOARD_AGENT_KEY in $CONFIG_FILE."
  exit 1
fi

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\t'/\\t}"
  s="${s//$'\r'/\\r}"
  s="${s//$'\n'/\\n}"
  printf '%s' "$s"
}

# Sceglie lo scanner disponibile. clamdscan e' molto piu' veloce (firme gia' in
# RAM nel demone); clamscan e' il ripiego quando clamd non risponde.
pick_scanner() {
  if command -v clamdscan >/dev/null 2>&1 && clamdscan --ping 1 >/dev/null 2>&1; then
    SCANNER=(clamdscan --multiscan --fdpass --infected)
    return 0
  elif command -v clamscan >/dev/null 2>&1; then
    SCANNER=(clamscan --recursive --infected)
    return 0
  fi
  return 1
}

report_result() {
  local command_id="$1" verdict="$2" target="$3" findings_json="$4" error_message="$5"
  local payload
  payload="{\"hostname\":\"$(json_escape "$(hostname)")\""
  payload+=",\"path\":\"$(json_escape "$target")\""
  payload+=",\"verdict\":\"${verdict}\""
  payload+=",\"commandId\":${command_id}"
  payload+=",\"findings\":${findings_json}"
  payload+=",\"errorMessage\":\"$(json_escape "$error_message")\"}"

  if curl -sS -f --max-time "$CURL_TIMEOUT" \
       -H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}" \
       -X POST "${DASHBOARD_URL%/}/api/scan/report" \
       -H 'Content-Type: application/json' \
       -d "$payload" > /dev/null; then
    log "comando ${command_id}: esito ${verdict} inviato"
  else
    log "comando ${command_id}: invio dell'esito FALLITO (la console lo chiudera' per timeout)"
  fi
}

run_command() {
  local command_id="$1" targets_raw="$2"

  # I target arrivano uno per riga; li mettiamo in un array per passarli tutti
  # a un'unica invocazione dello scanner.
  local targets=()
  while IFS= read -r line; do
    [[ -n "$line" ]] && targets+=("$line")
  done <<< "$targets_raw"

  if [[ ${#targets[@]} -eq 0 ]]; then
    report_result "$command_id" "ERROR" "$targets_raw" "[]" "Comando senza percorsi da scansionare."
    return
  fi

  if ! pick_scanner; then
    report_result "$command_id" "ERROR" "${targets[*]}" "[]" \
      "Ne' clamdscan ne' clamscan sono installati su questa macchina."
    return
  fi

  log "comando ${command_id}: scansione di ${targets[*]}"
  local output exit_code
  output="$("${SCANNER[@]}" "${targets[@]}" 2>&1)"
  exit_code=$?

  # Righe nel formato "<path>: <FIRMA> FOUND": e' lo stesso che la console sa
  # gia' interpretare, quindi le inoltriamo cosi' come sono.
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
    report_result "$command_id" "VIRUS_FOUND" "${targets[*]}" "$findings_json" ""
  elif [[ "$exit_code" -eq 0 ]]; then
    # Nessun virus: qui l'esito pulito va riportato comunque, perche' e' una
    # scansione che un utente ha lanciato dalla console e sta aspettando.
    report_result "$command_id" "OK" "${targets[*]}" "[]" ""
  else
    report_result "$command_id" "ERROR" "${targets[*]}" "[]" \
      "Scan fallito (exit code ${exit_code}). Output: $(printf '%s\n' "$output" | tail -5)"
  fi
}

poll_once() {
  local response
  response="$(curl -sS -f --max-time "$CURL_TIMEOUT" \
      -H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}" \
      "${DASHBOARD_URL%/}/api/agent/commands?format=text" 2>/dev/null)" || {
    log "console non raggiungibile, riprovo al prossimo giro"
    return 0
  }

  [[ -z "$response" ]] && return 0

  # Ogni riga: "<id> <target codificato in base64>". Il base64 evita qualunque
  # problema di spazi, apici o a capo nei percorsi.
  while IFS=' ' read -r command_id encoded; do
    [[ -z "$command_id" || -z "$encoded" ]] && continue
    local targets_raw
    targets_raw="$(printf '%s' "$encoded" | base64 -d 2>/dev/null)" || {
      log "comando ${command_id}: target non decodificabile, salto"
      continue
    }
    run_command "$command_id" "$targets_raw"
  done <<< "$response"
}

case "${1:---loop}" in
  --once)
    poll_once
    ;;
  --loop)
    log "agent avviato: console ${DASHBOARD_URL%/}, poll ogni ${POLL_SECONDS}s"
    while true; do
      poll_once
      sleep "$POLL_SECONDS"
    done
    ;;
  *)
    grep '^#' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
