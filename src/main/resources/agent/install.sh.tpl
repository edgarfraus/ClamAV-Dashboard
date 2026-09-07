#!/usr/bin/env bash
#
# Agent ClaimAV — script di installazione generato dalla console.
#   Endpoint: @@ENDPOINT_NAME@@
#   Console:  @@CONSOLE_URL@@
#
# La chiave qui sotto vale SOLO per questo endpoint: permette di scaricare
# l'agent e di inviare i report, nient'altro. Se trapela, rigenerala dalla
# console (Admin > Endpoints > Rotate) e reinstalla.
#
# USO:
#   curl -fsSL '@@CONSOLE_URL@@/agent/install.sh?key=@@AGENT_KEY@@' | sudo bash
# oppure, per passare opzioni:
#   curl -fsSL '@@CONSOLE_URL@@/agent/install.sh?key=@@AGENT_KEY@@' -o install.sh
#   sudo bash install.sh --watch-path /home --watch-path /var/www
#
set -euo pipefail

CONSOLE_URL="@@CONSOLE_URL@@"
AGENT_KEY="@@AGENT_KEY@@"
ENDPOINT_NAME="@@ENDPOINT_NAME@@"

log()  { echo -e "\033[1;34m[*]\033[0m $*"; }
ok()   { echo -e "\033[1;32m[OK]\033[0m $*"; }
warn() { echo -e "\033[1;33m[!]\033[0m $*"; }
err()  { echo -e "\033[1;31m[ERRORE]\033[0m $*" >&2; }

if [[ $EUID -ne 0 ]]; then
  err "Esegui con sudo/root."
  exit 1
fi

fetch() {
  # $1 = path sulla console, $2 = file di destinazione
  curl -fsSL -H "X-Agent-Key: ${AGENT_KEY}" "${CONSOLE_URL%/}/$1" -o "$2"
}

# L'IP della console serve per aprire il firewall solo verso di lei. Se la console
# e' raggiunta per nome DNS non passiamo --console-ip: ufw e firewalld vogliono un
# indirizzo, e una regola sbagliata e' peggio di nessuna regola.
CONSOLE_HOST=$(printf '%s' "$CONSOLE_URL" | sed -E 's#^[a-zA-Z]+://##; s#[:/].*$##')
CONSOLE_IP_ARGS=()
if [[ "$CONSOLE_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  CONSOLE_IP_ARGS=(--console-ip "$CONSOLE_HOST")
fi

# L'agent in polling: e' quello che fa funzionare il pulsante "Scan" della
# console. Va installato su Linux e su macOS allo stesso modo, cambia solo
# il gestore dei servizi.
install_poller_binary() {
  local tmp="$1"
  fetch "agent/files/clamav-agent-poll.sh" "$tmp/clamav-agent-poll.sh"
  install -m 700 "$tmp/clamav-agent-poll.sh" /usr/local/bin/clamav-agent-poll.sh
  ok "Agent installato in /usr/local/bin/clamav-agent-poll.sh"
}

install_linux() {
  log "Sistema Linux: installo clamd + protezione realtime (on-access)."
  local tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT

  log "Scarico gli script dell'agent dalla console..."
  fetch "agent/files/install-clamd-remote.sh" "$tmp/install-clamd-remote.sh"
  fetch "agent/files/clamav-onacc-report.sh"  "$tmp/clamav-onacc-report.sh"
  chmod +x "$tmp/install-clamd-remote.sh" "$tmp/clamav-onacc-report.sh"
  ok "Script scaricati."

  # --scan-system: clamd deve poter leggere tutto il filesystem per le scansioni
  #                su richiesta lanciate dalla console.
  # --on-access:   protezione realtime, con invio immediato dei rilevamenti.
  # --console-ip:  il firewall viene aperto solo verso la console.
  if [[ ${#CONSOLE_IP_ARGS[@]} -eq 0 ]]; then
    warn "Console raggiunta per nome ($CONSOLE_HOST): non tocco il firewall."
    warn "Se ne usi uno, apri la porta 3310 verso l'IP della console a mano."
  fi

  "$tmp/install-clamd-remote.sh" \
      --scan-system \
      "${CONSOLE_IP_ARGS[@]}" \
      --on-access \
      --console-url "$CONSOLE_URL" \
      --console-key "$AGENT_KEY" \
      "$@"

  # A questo punto /etc/clamav/console-report.conf esiste gia' (l'ha scritto
  # l'installer con URL e chiave): il poller legge da li'.
  log "Installo l'agent che esegue le scansioni richieste dalla console..."
  install_poller_binary "$tmp"

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
    ok "clamav-agent-poll.service attivo: le scansioni lanciate dalla console arrivano qui."
  else
    err "clamav-agent-poll.service non e' partito. Log:"
    journalctl -u clamav-agent-poll.service -n 20 --no-pager || true
  fi
}

install_macos() {
  warn "Sistema macOS: la protezione realtime NON e' disponibile."
  warn "L'on-access di ClamAV usa fanotify, che esiste solo su Linux."
  warn "Qui installo l'agent in modalita' scansione programmata + invio dei report."

  if ! command -v clamdscan >/dev/null 2>&1 && ! command -v clamscan >/dev/null 2>&1; then
    err "ClamAV non risulta installato. Installalo con Homebrew e rilancia:"
    err "    brew install clamav"
    exit 1
  fi

  local tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT

  log "Scarico lo scanner batch dalla console..."
  fetch "agent/files/clamav-telegram-alert.sh" "$tmp/clamav-scan-report.sh"
  install -m 700 "$tmp/clamav-scan-report.sh" /usr/local/bin/clamav-scan-report.sh
  ok "Scanner installato in /usr/local/bin/clamav-scan-report.sh"

  mkdir -p /etc/clamav
  umask 077
  {
    echo "# Generato dall'installer dell'agent ClaimAV il $(date '+%Y-%m-%d %H:%M:%S')"
    echo "DASHBOARD_URL='${CONSOLE_URL}'"
    echo "DASHBOARD_AGENT_KEY='${AGENT_KEY}'"
    echo "SCAN_PATHS_LIST='/Users /Applications'"
  } > /etc/clamav/console-report.conf
  chmod 600 /etc/clamav/console-report.conf
  umask 022
  ok "Configurazione salvata in /etc/clamav/console-report.conf (600)."

  cat > /Library/LaunchDaemons/com.claimav.agent.scan.plist << PLIST
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
    <key>StandardErrorPath</key>
    <string>/var/log/clamav-agent.log</string>
    <key>StandardOutPath</key>
    <string>/var/log/clamav-agent.log</string>
</dict>
</plist>
PLIST
  chmod 644 /Library/LaunchDaemons/com.claimav.agent.scan.plist
  launchctl unload /Library/LaunchDaemons/com.claimav.agent.scan.plist 2>/dev/null || true
  launchctl load  /Library/LaunchDaemons/com.claimav.agent.scan.plist
  ok "Scansione programmata attiva (ogni notte alle 02:30)."

  log "Installo l'agent che esegue le scansioni richieste dalla console..."
  install_poller_binary "$tmp"

  cat > /Library/LaunchDaemons/com.claimav.agent.poll.plist << PLIST
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
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardErrorPath</key>
    <string>/var/log/clamav-agent.log</string>
    <key>StandardOutPath</key>
    <string>/var/log/clamav-agent.log</string>
</dict>
</plist>
PLIST
  chmod 644 /Library/LaunchDaemons/com.claimav.agent.poll.plist
  launchctl unload /Library/LaunchDaemons/com.claimav.agent.poll.plist 2>/dev/null || true
  launchctl load  /Library/LaunchDaemons/com.claimav.agent.poll.plist
  ok "Agent attivo: le scansioni lanciate dalla console arrivano anche qui."

  echo
  log "Per una verifica immediata (fa una scansione vera e invia l'esito):"
  log "    sudo /usr/local/bin/clamav-scan-report.sh"
}

case "$(uname -s)" in
  Linux)  install_linux "$@" ;;
  Darwin) install_macos ;;
  *)
    err "Sistema non supportato da questo script: $(uname -s)"
    err "Per Windows usa: @@CONSOLE_URL@@/agent/install.ps1"
    exit 1
    ;;
esac

echo
ok "Agent '${ENDPOINT_NAME}' installato."
echo "    Console: ${CONSOLE_URL}"
