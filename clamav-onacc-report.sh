#!/bin/bash
#
# clamav-onacc-report.sh
#
# Inoltra alla ClaimAV Dashboard, in tempo reale, ogni rilevazione fatta dallo
# scanner on-access di ClamAV (clamonacc), via POST /api/scan/report.
#
# Perche' segue un log invece di usare VirusEvent di clamd:
#   VirusEvent NON scatta per le scansioni on-access. E' disabilitato di
#   proposito nel codice di ClamAV dalla 0.100 (virusaction() commentato in
#   onaccess_fan.c: "virusaction forks. This could be extraordinarily
#   problematic, lead to deadlocks..."), e non scatta nemmeno con
#   clamdscan --fdpass. L'unico posto dove compare il PATH REALE del file
#   infetto e' l'output di clamonacc, che logga:
#
#       <path>: <FIRMA> FOUND
#
#   (clamonacc/client/protocol.c: logg("%s%s FOUND", display_filename, colon)
#   per scantype >= STREAM, quindi sia con --fdpass che con --stream).
#   Con LogTime attivo la riga e' preceduta da "Gio Set  7 14:23:01 2026 -> ".
#
# USO:
#   clamav-onacc-report.sh --follow /var/log/clamav/clamonacc.log   # servizio
#   clamav-onacc-report.sh --test                                   # prova la connessione
#
# CONFIGURAZIONE: /etc/clamav/console-report.conf (chmod 600), formato:
#   DASHBOARD_URL=http://192.168.1.50:8080
#   DASHBOARD_AGENT_KEY=cav_...
# oppure, in alternativa (modo vecchio, utente OPERATOR dedicato):
#   DASHBOARD_API_USER=agent-fedora
#   DASHBOARD_API_PASSWORD=...
#
set -uo pipefail

CONFIG_FILE="${CONFIG_FILE:-/etc/clamav/console-report.conf}"
SPOOL_DIR="${SPOOL_DIR:-/var/lib/clamav-console-report/spool}"
DEDUP_DIR="${DEDUP_DIR:-/var/lib/clamav-console-report/dedup}"
# Stessa coppia path+firma non viene rinviata piu' di una volta ogni N secondi:
# un file infetto aperto in loop generebbe altrimenti una raffica di notifiche.
DEDUP_SECONDS="${DEDUP_SECONDS:-300}"
CURL_TIMEOUT="${CURL_TIMEOUT:-10}"

[[ -r "$CONFIG_FILE" ]] && . "$CONFIG_FILE"

DASHBOARD_URL="${DASHBOARD_URL:-}"
DASHBOARD_AGENT_KEY="${DASHBOARD_AGENT_KEY:-}"
DASHBOARD_API_USER="${DASHBOARD_API_USER:-}"
DASHBOARD_API_PASSWORD="${DASHBOARD_API_PASSWORD:-}"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >&2; }

# La chiave dell'endpoint (generata dalla console) e' il modo preferito: vale solo
# per questo endpoint. L'utente OPERATOR resta supportato per le installazioni
# fatte prima che le chiavi esistessero.
if [[ -n "$DASHBOARD_AGENT_KEY" ]]; then
  AUTH_ARGS=(-H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}")
elif [[ -n "$DASHBOARD_API_USER" && -n "$DASHBOARD_API_PASSWORD" ]]; then
  AUTH_ARGS=(-u "${DASHBOARD_API_USER}:${DASHBOARD_API_PASSWORD}")
else
  AUTH_ARGS=()
fi

if [[ -z "$DASHBOARD_URL" || ${#AUTH_ARGS[@]} -eq 0 ]]; then
  log "ERRORE: configurazione incompleta. Servono DASHBOARD_URL e DASHBOARD_AGENT_KEY"
  log "        (oppure DASHBOARD_API_USER + DASHBOARD_API_PASSWORD) in $CONFIG_FILE."
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

# POST del payload. Se la console non risponde il payload finisce nello spool e
# viene ritentato al prossimo evento: un malware trovato mentre la console e'
# giu' non deve sparire.
post_payload() {
  local payload="$1"
  curl -sS -f --max-time "$CURL_TIMEOUT" \
    "${AUTH_ARGS[@]}" \
    -X POST "${DASHBOARD_URL%/}/api/scan/report" \
    -H 'Content-Type: application/json' \
    -d "$payload" > /dev/null 2>&1
}

spool_payload() {
  local payload="$1"
  local f
  f="${SPOOL_DIR}/$(date +%s%N).json"
  printf '%s' "$payload" > "$f" 2>/dev/null && chmod 600 "$f" 2>/dev/null
  log "console non raggiungibile: evento messo in coda ($f)"
}

flush_spool() {
  local f
  shopt -s nullglob
  for f in "$SPOOL_DIR"/*.json; do
    if post_payload "$(cat "$f")"; then
      rm -f "$f"
      log "evento arretrato inviato ($(basename "$f"))"
    else
      break   # console ancora giu': riprovo al prossimo evento
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
    log "inviato: $path ($sig)"
  else
    spool_payload "$payload"
  fi
}

# Estrae path e firma da una riga di clamonacc e applica la deduplica.
process_line() {
  local line="$1"

  # Toglie il prefisso timestamp di ClamAV ("... -> "), se presente.
  line="${line##* -> }"
  [[ "$line" == *" FOUND" ]] || return 0

  local body="${line% FOUND}"      # "<path>: <FIRMA>"
  [[ "$body" == *": "* ]] || return 0
  local path="${body%: *}"         # il path puo' contenere ": ", la firma no
  local sig="${body##*: }"
  [[ -n "$path" && -n "$sig" ]] || return 0

  local now key marker last
  now=$(date +%s)
  # Deduplica su file invece che con un array associativo: funziona anche con
  # bash 3.x e sopravvive a un riavvio del servizio (niente raffica di duplicati
  # se il reporter riparte mentre il file infetto e' ancora li').
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
  # I marker vecchi non servono piu': li elimino ogni tanto per non accumularli.
  find "$DEDUP_DIR" -type f -mmin +120 -delete 2>/dev/null || true

  send_finding "$path" "$sig"
}

case "${1:-}" in
  --test)
    payload=$(printf '{"hostname":"%s","path":"%s","verdict":"ERROR","source":"realtime","errorMessage":"%s"}' \
      "$(json_escape "$(hostname)")" \
      "$(json_escape "clamav-onacc-report.sh --test")" \
      "Test di connettivita' dell'agente on-access: la console e le credenziali funzionano.")
    if post_payload "$payload"; then
      echo "OK: la console ha accettato il report di test (cercalo in Jobs, tipo REALTIME)."
      exit 0
    else
      echo "ERRORE: POST a ${DASHBOARD_URL%/}/api/scan/report fallito." >&2
      echo "Controlla URL, credenziali (utente OPERATOR) e raggiungibilita' di rete." >&2
      exit 1
    fi
    ;;
  --follow)
    LOGFILE="${2:-/var/log/clamav/clamonacc.log}"
    log "seguo $LOGFILE, inoltro a ${DASHBOARD_URL%/}/api/scan/report"
    # -n0: solo eventi nuovi. -F: sopravvive alla rotazione del log.
    tail -n0 -F "$LOGFILE" 2>/dev/null | while IFS= read -r line; do
      process_line "$line"
    done
    ;;
  *)
    grep '^#' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
