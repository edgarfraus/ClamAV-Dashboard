#!/bin/bash
#
# clamav-telegram-alert.sh
# Esegue uno scan via clamd (piu' veloce di clamscan a freddo) e, se vengono
# trovati file infetti (o lo scan fallisce), invia il risultato alla
# ClaimAV Dashboard tramite POST /api/scan/report. E' la dashboard a inviare
# l'alert Telegram (impostazioni admin -> Settings -> Telegram notifications),
# cosi' bot token e chat id restano in un solo posto.
#
# Requisiti:
#   - clamav-daemon (clamd) attivo e funzionante
#   - clamdscan installato (pacchetto clamav-daemon su Debian/Ubuntu)
#   - curl installato
#
# Configurazione: modifica le variabili qui sotto o esportale come
# variabili d'ambiente prima di lanciare lo script.

set -euo pipefail

### === CONFIGURAZIONE === ###

# URL base della ClaimAV Dashboard e credenziali (utente OPERATOR dedicato,
# non l'admin di default: vedi /admin/users).
# Se esiste, la configurazione scritta dall'installer ha la precedenza: cosi'
# questo script e quello on-access condividono URL e credenziali.
CONFIG_FILE="${CONFIG_FILE:-/etc/clamav/console-report.conf}"
[[ -r "$CONFIG_FILE" ]] && . "$CONFIG_FILE"

DASHBOARD_URL="${DASHBOARD_URL:-http://localhost:8080}"
DASHBOARD_AGENT_KEY="${DASHBOARD_AGENT_KEY:-}"
DASHBOARD_API_USER="${DASHBOARD_API_USER:-}"
DASHBOARD_API_PASSWORD="${DASHBOARD_API_PASSWORD:-}"

# Chiave dell'endpoint (consigliata) o, in alternativa, utente OPERATOR.
if [[ -n "$DASHBOARD_AGENT_KEY" ]]; then
  AUTH_ARGS=(-H "X-Agent-Key: ${DASHBOARD_AGENT_KEY}")
elif [[ -n "$DASHBOARD_API_USER" && -n "$DASHBOARD_API_PASSWORD" ]]; then
  AUTH_ARGS=(-u "${DASHBOARD_API_USER}:${DASHBOARD_API_PASSWORD}")
else
  echo "ERRORE: serve DASHBOARD_AGENT_KEY (o DASHBOARD_API_USER + DASHBOARD_API_PASSWORD)." >&2
  exit 1
fi

# Percorsi da scansionare. L'installer puo' imporli scrivendo SCAN_PATHS_LIST
# nel file di configurazione (su macOS, per esempio, i path Linux non esistono).
if [[ -n "${SCAN_PATHS_LIST:-}" ]]; then
  read -r -a SCAN_PATHS <<< "$SCAN_PATHS_LIST"
else
  # Evita /proc /sys e mount di rete lenti.
  SCAN_PATHS=(
    "/home"
    "/tmp"
    "/var/www"
    "/opt"
  )
fi

# Percorsi da escludere (regex compatibili con clamdscan --exclude-dir)
EXCLUDE_DIRS=(
  "^/var/lib/docker"
  "\.git"
  "node_modules"
  "^/home/[^/]+/\.cache"
)

# File di log locale (per storico, indipendente dalla dashboard)
LOG_FILE="/var/log/clamav-telegram-alert.log"

### === FINE CONFIGURAZIONE === ###

TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"
HOSTNAME="$(hostname)"

# Costruisci gli argomenti --exclude-dir
EXCLUDE_ARGS=()
for pattern in "${EXCLUDE_DIRS[@]}"; do
  EXCLUDE_ARGS+=(--exclude-dir="${pattern}")
done

echo "[$TIMESTAMP] Avvio scan su: ${SCAN_PATHS[*]}" >> "$LOG_FILE"

# clamdscan usa il demone clamd (signature gia' in RAM: molto piu' veloce)
# --multiscan --fdpass abilita scan multithreaded passando i file descriptor
# -i mostra solo i file infetti nell'output (piu' facile da parsare)
#
# clamdscan ha bisogno che clamd sia in esecuzione. Dove non lo e' (tipicamente
# macOS con ClamAV da Homebrew, dove il demone non parte da solo) si ripiega su
# clamscan, che e' autonomo: piu' lento, ma la scansione avviene comunque.
NO_SCANNER=0
if command -v clamdscan >/dev/null 2>&1 && clamdscan --ping 1 >/dev/null 2>&1; then
  SCANNER=(clamdscan --multiscan --fdpass --infected)
elif command -v clamscan >/dev/null 2>&1; then
  SCANNER=(clamscan --recursive --infected)
  echo "[$TIMESTAMP] clamd non raggiungibile: uso clamscan (piu' lento)" >> "$LOG_FILE"
else
  echo "[$TIMESTAMP] ne clamdscan ne clamscan disponibili" >> "$LOG_FILE"
  NO_SCANNER=1
fi

# set +e/-e attorno alla chiamata: con "set -e" attivo, un exit code diverso
# da 0 (1 = infetti trovati, 2 = errore) farebbe terminare subito lo script
# PRIMA di leggere $?, quindi va disattivato solo per questo comando.
if [ "$NO_SCANNER" -eq 0 ]; then
  set +e
  SCAN_OUTPUT="$("${SCANNER[@]}" \
    "${EXCLUDE_ARGS[@]}" \
    "${SCAN_PATHS[@]}" 2>&1)"
  EXIT_CODE=$?
  set -e
else
  # Nessuno scanner: lo segnaliamo come errore di scan, cosi' la console lo vede
  # invece di limitarsi a non ricevere piu' nulla da questa macchina.
  SCAN_OUTPUT="Ne' clamdscan ne' clamscan sono installati su questa macchina."
  EXIT_CODE=2
fi

echo "$SCAN_OUTPUT" >> "$LOG_FILE"
echo "[$TIMESTAMP] Scan completato con exit code $EXIT_CODE" >> "$LOG_FILE"

# Estrai solo le righe con file infetti (formato: "path: SIGNATURE FOUND")
INFECTED_LINES="$(echo "$SCAN_OUTPUT" | grep "FOUND$" || true)"

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  printf '%s' "$s"
}

# Costruisci l'array JSON delle righe infette (una stringa raw per riga,
# il parsing "path: SIGNATURE FOUND" lo fa la dashboard lato server).
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

send_report() {
  local verdict="$1"
  local error_message="$2"
  local payload
  payload="{\"hostname\":\"$(json_escape "$HOSTNAME")\",\"path\":\"$(json_escape "${SCAN_PATHS[*]}")\",\"verdict\":\"${verdict}\",\"findings\":${FINDINGS_JSON},\"errorMessage\":\"$(json_escape "$error_message")\"}"

  curl -s "${AUTH_ARGS[@]}" \
    -X POST "${DASHBOARD_URL%/}/api/scan/report" \
    -H 'Content-Type: application/json' \
    -d "$payload" \
    > /dev/null
}

if [ -n "$INFECTED_LINES" ]; then
  COUNT="$(echo "$INFECTED_LINES" | wc -l)"

  send_report "VIRUS_FOUND" ""
  echo "[$TIMESTAMP] Report inviato alla dashboard (${COUNT} file infetti)" >> "$LOG_FILE"

elif [ "$EXIT_CODE" -eq 2 ]; then
  # Errore di scan (es. clamd non raggiungibile) - avvisa comunque
  send_report "ERROR" "Scan fallito (exit code ${EXIT_CODE}) su ${SCAN_PATHS[*]}. Output: $(echo "$SCAN_OUTPUT" | tail -5)"
  echo "[$TIMESTAMP] Errore di scan, report di warning inviato alla dashboard" >> "$LOG_FILE"

else
  echo "[$TIMESTAMP] Nessuna infezione trovata, nessun report inviato" >> "$LOG_FILE"
fi

exit 0
