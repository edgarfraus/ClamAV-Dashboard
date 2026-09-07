#!/usr/bin/env bash
#
# Agent ClaimAV — installer generato dalla console.
#   Endpoint: @@ENDPOINT_NAME@@
#   Console:  @@CONSOLE_URL@@
#
# Questo file e' autosufficiente: contiene gia' la chiave di questo endpoint e
# tutti gli script dell'agent. Scaricalo ed eseguilo, non serve altro.
#
#   sudo bash install-agent.sh              # ti chiede cosa installare
#   sudo bash install-agent.sh --all        # installa tutto senza chiedere
#
# La chiave vale SOLO per questo endpoint: consente di inviare i report e di
# ricevere le scansioni richieste dalla console, nient'altro. Se trapela,
# rigenerala dalla console (Admin > Endpoints > Rotate) e reinstalla.
#
set -euo pipefail

CONSOLE_URL="@@CONSOLE_URL@@"
AGENT_KEY="@@AGENT_KEY@@"
ENDPOINT_NAME="@@ENDPOINT_NAME@@"

CONFIG_FILE="/etc/clamav/console-report.conf"

log()  { echo -e "\033[1;34m[*]\033[0m $*"; }
ok()   { echo -e "\033[1;32m[OK]\033[0m $*"; }
warn() { echo -e "\033[1;33m[!]\033[0m $*"; }
err()  { echo -e "\033[1;31m[ERRORE]\033[0m $*" >&2; }

OS="$(uname -s)"

# ---------------------------------------------------------------------------
# Cosa installare
# ---------------------------------------------------------------------------
WANT_REALTIME=""
WANT_CENTRAL=""
WANT_SCHEDULED=""
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all)        WANT_REALTIME=1; WANT_CENTRAL=1; WANT_SCHEDULED=1; shift ;;
    --realtime)   WANT_REALTIME=1; shift ;;
    --central)    WANT_CENTRAL=1; shift ;;
    --scheduled)  WANT_SCHEDULED=1; shift ;;
    --no-realtime)  WANT_REALTIME=0; shift ;;
    --no-central)   WANT_CENTRAL=0; shift ;;
    --no-scheduled) WANT_SCHEDULED=0; shift ;;
    # Stampa solo il blocco di commento iniziale: "grep '^#'" prenderebbe anche
    # i commenti degli script incorporati piu' sotto.
    -h|--help) awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"; exit 0 ;;
    # tutto il resto viene passato a install-clamd-remote.sh (es. --watch-path)
    *) EXTRA_ARGS+=("$1"); shift ;;
  esac
done

# Il controllo di root sta dopo il parsing degli argomenti, cosi' "--help"
# funziona anche senza sudo, e prima del menu, per non far rispondere l'utente
# a tre domande e solo allora dirgli che servono i privilegi.
if [[ $EUID -ne 0 ]]; then
  err "Esegui con sudo/root:  sudo bash $0"
  exit 1
fi

ask() {
  # $1 = domanda, $2 = default (S/n)
  local answer
  read -rp "$(echo -e "\033[1;36m?\033[0m $1 [$2] ")" answer || answer=""
  answer="${answer:-$2}"
  [[ "$answer" =~ ^[SsYy] ]]
}

# Se non e' stata scelta nessuna opzione da riga di comando, chiediamo — ma solo
# se c'e' un terminale. Con "curl ... | bash" lo stdin e' la pipe, non la
# tastiera: li' installiamo tutto, che e' la scelta sensata di default.
if [[ -z "$WANT_REALTIME$WANT_CENTRAL$WANT_SCHEDULED" ]]; then
  if [[ -t 0 ]]; then
    echo
    echo "  Agent ClaimAV — endpoint '${ENDPOINT_NAME}'"
    echo "  Console: ${CONSOLE_URL}"
    echo
    echo "  Scegli cosa attivare su questa macchina:"
    echo

    if [[ "$OS" == "Linux" ]]; then
      echo "  1) Protezione realtime (on-access)"
      echo "     I file vengono controllati nel momento in cui vengono scritti o"
      echo "     aperti, e ogni rilevazione arriva subito in console e su Telegram."
      ask "Attivare la protezione realtime?" "S" && WANT_REALTIME=1 || WANT_REALTIME=0
    else
      warn "Protezione realtime non disponibile su $OS: l'on-access di ClamAV usa"
      warn "fanotify, che esiste solo su Linux. Salto questa opzione."
      WANT_REALTIME=0
    fi

    echo
    echo "  2) Scansioni centralizzate"
    echo "     Il pulsante 'Scan' della console fa partire la scansione qui, in"
    echo "     locale: niente problemi di permessi o di porte da aprire."
    ask "Attivare le scansioni dalla console?" "S" && WANT_CENTRAL=1 || WANT_CENTRAL=0

    echo
    echo "  3) Scansione programmata"
    echo "     Una scansione completa ogni notte alle 02:30, con esito in console."
    ask "Attivare la scansione programmata?" "S" && WANT_SCHEDULED=1 || WANT_SCHEDULED=0
    echo
  else
    log "Nessun terminale interattivo (script in pipe): installo tutto."
    log "Per scegliere, scarica il file ed eseguilo, oppure usa --realtime/--central/--scheduled."
    WANT_REALTIME=1; WANT_CENTRAL=1; WANT_SCHEDULED=1
  fi
fi

# Normalizza i valori non impostati a 0
WANT_REALTIME="${WANT_REALTIME:-0}"
WANT_CENTRAL="${WANT_CENTRAL:-0}"
WANT_SCHEDULED="${WANT_SCHEDULED:-0}"

if [[ "$OS" != "Linux" && "$WANT_REALTIME" == "1" ]]; then
  warn "Protezione realtime non supportata su $OS (serve fanotify, solo Linux): la salto."
  WANT_REALTIME=0
fi

if [[ "$WANT_REALTIME$WANT_CENTRAL$WANT_SCHEDULED" == "000" ]]; then
  err "Non hai selezionato nulla da installare."
  exit 1
fi

# ---------------------------------------------------------------------------
# Script incorporati (nessun download: sono gia' dentro questo file)
# ---------------------------------------------------------------------------
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

write_embedded_scripts() {
  cat > "$WORKDIR/install-clamd-remote.sh" <<'__CLAIMAV_EMBED_CLAMD_INSTALLER__'
@@EMBED_CLAMD_INSTALLER@@
__CLAIMAV_EMBED_CLAMD_INSTALLER__

  cat > "$WORKDIR/clamav-onacc-report.sh" <<'__CLAIMAV_EMBED_ONACC_REPORT__'
@@EMBED_ONACC_REPORT@@
__CLAIMAV_EMBED_ONACC_REPORT__

  cat > "$WORKDIR/clamav-agent-poll.sh" <<'__CLAIMAV_EMBED_AGENT_POLL__'
@@EMBED_AGENT_POLL@@
__CLAIMAV_EMBED_AGENT_POLL__

  cat > "$WORKDIR/clamav-scan-report.sh" <<'__CLAIMAV_EMBED_BATCH_SCAN__'
@@EMBED_BATCH_SCAN@@
__CLAIMAV_EMBED_BATCH_SCAN__

  chmod +x "$WORKDIR"/*.sh
}

write_embedded_scripts
ok "Script dell'agent estratti (nessun download necessario)."

# ---------------------------------------------------------------------------
# Configurazione condivisa da tutti i componenti
# ---------------------------------------------------------------------------
shell_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

write_agent_config() {
  mkdir -p /etc/clamav
  umask 077
  {
    echo "# Generato dall'installer dell'agent ClaimAV il $(date '+%Y-%m-%d %H:%M:%S')"
    echo "# Endpoint: ${ENDPOINT_NAME}"
    echo "DASHBOARD_URL=$(shell_quote "$CONSOLE_URL")"
    echo "DASHBOARD_AGENT_KEY=$(shell_quote "$AGENT_KEY")"
    if [[ "$OS" == "Darwin" ]]; then
      echo "SCAN_PATHS_LIST=$(shell_quote "/Users /Applications")"
    fi
  } > "$CONFIG_FILE"
  chmod 600 "$CONFIG_FILE"
  umask 022
  ok "Configurazione salvata in $CONFIG_FILE (permessi 600, solo root)."
}

# L'IP della console serve per aprire il firewall solo verso di lei. Se la console
# e' raggiunta per nome DNS non passiamo --console-ip: ufw e firewalld vogliono un
# indirizzo, e una regola sbagliata e' peggio di nessuna regola.
CONSOLE_HOST=$(printf '%s' "$CONSOLE_URL" | sed -E 's#^[a-zA-Z]+://##; s#[:/].*$##')
CONSOLE_IP_ARGS=()
if [[ "$CONSOLE_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  CONSOLE_IP_ARGS=(--console-ip "$CONSOLE_HOST")
fi

install_poller_service_linux() {
  install -m 700 "$WORKDIR/clamav-agent-poll.sh" /usr/local/bin/clamav-agent-poll.sh
  cat > /etc/systemd/system/clamav-agent-poll.service << EOF
[Unit]
Description=Agent ClaimAV: esegue le scansioni richieste dalla console
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
ExecStart=/usr/local/bin/clamav-agent-poll.sh --loop
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable --now clamav-agent-poll.service || true
  sleep 2
  if systemctl is-active --quiet clamav-agent-poll.service; then
    ok "Scansioni centralizzate attive: il pulsante Scan della console arriva qui."
  else
    err "clamav-agent-poll.service non e' partito. Log:"
    journalctl -u clamav-agent-poll.service -n 20 --no-pager || true
  fi
}

install_scheduled_linux() {
  install -m 700 "$WORKDIR/clamav-scan-report.sh" /usr/local/bin/clamav-scan-report.sh
  cat > /etc/systemd/system/clamav-scheduled-scan.service << 'EOF'
[Unit]
Description=Scansione ClamAV programmata (esito inviato alla ClaimAV console)

[Service]
Type=oneshot
User=root
ExecStart=/usr/local/bin/clamav-scan-report.sh
EOF
  cat > /etc/systemd/system/clamav-scheduled-scan.timer << 'EOF'
[Unit]
Description=Scansione ClamAV notturna

[Timer]
OnCalendar=*-*-* 02:30:00
Persistent=true

[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable --now clamav-scheduled-scan.timer || true
  ok "Scansione programmata attiva (ogni notte alle 02:30)."
}

# ---------------------------------------------------------------------------
# Linux
# ---------------------------------------------------------------------------
install_linux() {
  write_agent_config

  # clamd serve a tutti i componenti (il poller preferisce clamdscan, che e'
  # molto piu' veloce di clamscan perche' le firme sono gia' in RAM nel demone).
  local installer_args=(--scan-system "${CONSOLE_IP_ARGS[@]}")
  if [[ "$WANT_REALTIME" == "1" ]]; then
    installer_args+=(--on-access --console-url "$CONSOLE_URL" --console-key "$AGENT_KEY")
  fi
  if [[ ${#CONSOLE_IP_ARGS[@]} -eq 0 ]]; then
    warn "Console raggiunta per nome ($CONSOLE_HOST): non tocco il firewall."
    warn "Se ne usi uno, apri la porta 3310 verso l'IP della console a mano."
  fi

  log "Installo e configuro clamd..."
  "$WORKDIR/install-clamd-remote.sh" "${installer_args[@]}" "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"

  # L'installer riscrive la config quando usa --on-access: la riallineiamo
  # comunque, cosi' il contenuto e' lo stesso in tutti i casi.
  write_agent_config

  [[ "$WANT_CENTRAL"   == "1" ]] && install_poller_service_linux
  [[ "$WANT_SCHEDULED" == "1" ]] && install_scheduled_linux
  return 0
}

# ---------------------------------------------------------------------------
# macOS
# ---------------------------------------------------------------------------
install_macos() {
  if ! command -v clamdscan >/dev/null 2>&1 && ! command -v clamscan >/dev/null 2>&1; then
    err "ClamAV non risulta installato. Installalo con Homebrew e rilancia:"
    err "    brew install clamav"
    exit 1
  fi

  write_agent_config

  if [[ "$WANT_CENTRAL" == "1" ]]; then
    install -m 700 "$WORKDIR/clamav-agent-poll.sh" /usr/local/bin/clamav-agent-poll.sh
    cat > /Library/LaunchDaemons/com.claimav.agent.poll.plist << 'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.claimav.agent.poll</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>/usr/local/bin/clamav-agent-poll.sh</string>
        <string>--loop</string>
    </array>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>StandardErrorPath</key><string>/var/log/clamav-agent.log</string>
    <key>StandardOutPath</key><string>/var/log/clamav-agent.log</string>
</dict>
</plist>
PLIST
    chmod 644 /Library/LaunchDaemons/com.claimav.agent.poll.plist
    launchctl unload /Library/LaunchDaemons/com.claimav.agent.poll.plist 2>/dev/null || true
    launchctl load  /Library/LaunchDaemons/com.claimav.agent.poll.plist
    ok "Scansioni centralizzate attive: il pulsante Scan della console arriva qui."
  fi

  if [[ "$WANT_SCHEDULED" == "1" ]]; then
    install -m 700 "$WORKDIR/clamav-scan-report.sh" /usr/local/bin/clamav-scan-report.sh
    cat > /Library/LaunchDaemons/com.claimav.agent.scan.plist << 'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.claimav.agent.scan</string>
    <key>ProgramArguments</key>
    <array>
        <string>/bin/bash</string>
        <string>/usr/local/bin/clamav-scan-report.sh</string>
    </array>
    <key>StartCalendarInterval</key>
    <dict>
        <key>Hour</key><integer>2</integer>
        <key>Minute</key><integer>30</integer>
    </dict>
    <key>StandardErrorPath</key><string>/var/log/clamav-agent.log</string>
    <key>StandardOutPath</key><string>/var/log/clamav-agent.log</string>
</dict>
</plist>
PLIST
    chmod 644 /Library/LaunchDaemons/com.claimav.agent.scan.plist
    launchctl unload /Library/LaunchDaemons/com.claimav.agent.scan.plist 2>/dev/null || true
    launchctl load  /Library/LaunchDaemons/com.claimav.agent.scan.plist
    ok "Scansione programmata attiva (ogni notte alle 02:30)."
  fi
  return 0
}

case "$OS" in
  Linux)  install_linux ;;
  Darwin) install_macos ;;
  *)
    err "Sistema non supportato da questo script: $OS"
    err "Per Windows scarica install.ps1 dalla console."
    exit 1
    ;;
esac

# ---------------------------------------------------------------------------
# Verifica finale
# ---------------------------------------------------------------------------
echo
log "Verifico che la console accetti i report da questa macchina..."
if curl -sS -f --max-time 15 \
     -H "X-Agent-Key: ${AGENT_KEY}" \
     "${CONSOLE_URL%/}/api/agent/commands?format=text" >/dev/null 2>&1; then
  ok "Console raggiungibile e chiave accettata."
else
  warn "Non sono riuscito a contattare ${CONSOLE_URL}."
  warn "Controlla rete, URL e che la chiave non sia stata rigenerata nella console."
fi

echo
ok "Agent '${ENDPOINT_NAME}' installato."
echo "    Realtime (on-access):       $([[ "$WANT_REALTIME"  == "1" ]] && echo 'attivo' || echo 'non installato')"
echo "    Scansioni dalla console:    $([[ "$WANT_CENTRAL"   == "1" ]] && echo 'attive' || echo 'non installate')"
echo "    Scansione programmata:      $([[ "$WANT_SCHEDULED" == "1" ]] && echo 'attiva (02:30)' || echo 'non installata')"
echo "    Configurazione:             $CONFIG_FILE"
