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
DASHBOARD_URL="${DASHBOARD_URL:-http://localhost:8080}"
DASHBOARD_API_USER="${DASHBOARD_API_USER:-INSERISCI_UTENTE_OPERATOR}"
DASHBOARD_API_PASSWORD="${DASHBOARD_API_PASSWORD:-INSERISCI_PASSWORD}"

# Percorsi da scansionare (spazio-separati). Evita /proc /sys e mount di rete lenti.
SCAN_PATHS=(
  "/home"
  "/tmp"
  "/var/www"
  "/opt"
)

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
# set +e/-e attorno alla chiamata: con "set -e" attivo, un exit code diverso
# da 0 (1 = infetti trovati, 2 = errore) farebbe terminare subito lo script
# PRIMA di leggere $?, quindi va disattivato solo per questo comando.
set +e
SCAN_OUTPUT="$(clamdscan \
  --multiscan \
  --fdpass \
  --infected \
  "${EXCLUDE_ARGS[@]}" \
  "${SCAN_PATHS[@]}" 2>&1)"
EXIT_CODE=$?
set -e

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

  curl -s -u "${DASHBOARD_API_USER}:${DASHBOARD_API_PASSWORD}" \
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
  send_report "ERROR" "clamd non raggiungibile o errore durante lo scan (exit code ${EXIT_CODE}). Controlla ${LOG_FILE}"
  echo "[$TIMESTAMP] Errore di scan, report di warning inviato alla dashboard" >> "$LOG_FILE"

else
  echo "[$TIMESTAMP] Nessuna infezione trovata, nessun report inviato" >> "$LOG_FILE"
fi

exit 0
