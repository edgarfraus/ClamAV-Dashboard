#!/usr/bin/env bash
#
# install-clamd-remote.sh (multi-distro)
#
# Rileva automaticamente la distribuzione Linux e il relativo package manager,
# installa clamd (il demone ClamAV) e lo configura per accettare connessioni
# TCP remote, gestendo anche il problema noto della socket-activation di
# systemd su alcune distro (Debian/Ubuntu "bookworm"+, Arch) che ignora
# TCPSocket/TCPAddr nel file di configurazione quando e' presente una
# clamav-daemon.socket / clamd.socket.
#
# Distro supportate (auto-rilevate):
#   - Debian / Ubuntu / Raspberry Pi OS  -> apt
#   - RHEL / CentOS / Rocky / AlmaLinux / Fedora -> dnf (fallback yum)
#   - Arch Linux / Manjaro               -> pacman
#   - openSUSE                            -> zypper
#   - Alpine Linux                        -> apk (init OpenRC, non systemd)
#
# USO:
#   sudo ./install-clamd-remote.sh [opzioni]
#
# OPZIONI:
#   --port PORTA         Porta TCP di clamd (default: 3310)
#   --bind INDIRIZZO      Indirizzo su cui clamd resta in ascolto (default: 0.0.0.0)
#   --console-ip IP       IP della console web: se indicato, il firewall (ufw o
#                          firewalld, se presenti) viene aperto SOLO per quell'IP.
#   --skip-install        Non installare i pacchetti (solo riconfigura)
#   -h, --help             Mostra questo aiuto
#
# ESEMPIO:
#   sudo ./install-clamd-remote.sh --console-ip 192.168.1.50
#
set -euo pipefail

PORT=3310
BIND_ADDR="0.0.0.0"
CONSOLE_IP=""
SKIP_INSTALL=0

log()  { echo -e "\033[1;34m[*]\033[0m $*"; }
ok()   { echo -e "\033[1;32m[OK]\033[0m $*"; }
warn() { echo -e "\033[1;33m[!]\033[0m $*"; }
err()  { echo -e "\033[1;31m[ERRORE]\033[0m $*" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --bind) BIND_ADDR="$2"; shift 2 ;;
    --console-ip) CONSOLE_IP="$2"; shift 2 ;;
    --skip-install) SKIP_INSTALL=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) err "Opzione sconosciuta: $1"; exit 1 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  err "Questo script deve essere eseguito con sudo/root."
  exit 1
fi

# ---------------------------------------------------------------------------
# 1) Rilevamento distro / package manager
# ---------------------------------------------------------------------------
PKG_MGR=""
if command -v apt-get >/dev/null 2>&1; then PKG_MGR="apt"
elif command -v dnf >/dev/null 2>&1; then PKG_MGR="dnf"
elif command -v yum >/dev/null 2>&1; then PKG_MGR="yum"
elif command -v pacman >/dev/null 2>&1; then PKG_MGR="pacman"
elif command -v zypper >/dev/null 2>&1; then PKG_MGR="zypper"
elif command -v apk >/dev/null 2>&1; then PKG_MGR="apk"
else
  err "Nessun package manager supportato trovato (apt/dnf/yum/pacman/zypper/apk)."
  exit 1
fi

DISTRO_ID="sconosciuta"
[[ -f /etc/os-release ]] && DISTRO_ID=$(. /etc/os-release && echo "${PRETTY_NAME:-$ID}")
log "Distro rilevata: $DISTRO_ID  ->  package manager: $PKG_MGR"

# Rilevamento init system (systemd vs OpenRC, es. Alpine)
INIT_SYS="systemd"
if [[ "$PKG_MGR" == "apk" ]] || ! command -v systemctl >/dev/null 2>&1; then
  INIT_SYS="openrc"
fi
log "Init system: $INIT_SYS"

# ---------------------------------------------------------------------------
# 2) Installazione pacchetti (per distro)
# ---------------------------------------------------------------------------
if [[ $SKIP_INSTALL -eq 0 ]]; then
  log "Installo clamd per questa distro..."
  case "$PKG_MGR" in
    apt)
      apt-get update -qq
      DEBIAN_FRONTEND=noninteractive apt-get install -y -qq clamav-daemon clamav-freshclam
      ;;
    dnf|yum)
      if grep -qiE '^ID=(rhel|centos|rocky|almalinux)' /etc/os-release 2>/dev/null; then
        log "Distro RHEL-like: provo ad abilitare EPEL (se non gia' presente)..."
        "$PKG_MGR" install -y epel-release 2>/dev/null || warn "epel-release non installato automaticamente: se l'installazione di clamav fallisce, abilita EPEL manualmente."
      fi
      "$PKG_MGR" install -y clamav clamav-update clamd 2>/dev/null || \
        "$PKG_MGR" install -y clamav clamd 2>/dev/null || \
        "$PKG_MGR" install -y clamav
      ;;
    pacman)
      pacman -Sy --noconfirm --needed clamav
      ;;
    zypper)
      zypper --non-interactive install clamav
      ;;
    apk)
      apk update
      apk add clamav clamav-daemon
      ;;
  esac
  ok "Pacchetti installati."
else
  log "Salto installazione pacchetti (--skip-install)."
fi

# ---------------------------------------------------------------------------
# 3) Rilevamento file di configurazione clamd.conf
# ---------------------------------------------------------------------------
CLAMD_CONF=""
for candidate in /etc/clamav/clamd.conf /etc/clamd.d/scan.conf /etc/clamd.conf; do
  if [[ -f "$candidate" ]]; then
    CLAMD_CONF="$candidate"
    break
  fi
done

if [[ -z "$CLAMD_CONF" ]]; then
  err "Non trovo un file di configurazione clamd (controllato: /etc/clamav/clamd.conf, /etc/clamd.d/scan.conf, /etc/clamd.conf)."
  err "L'installazione del pacchetto potrebbe non aver creato il config di default: controlla manualmente."
  exit 1
fi
log "File di configurazione: $CLAMD_CONF"

# ---------------------------------------------------------------------------
# 4) Aggiorna il database firme (freshclam)
# ---------------------------------------------------------------------------
log "Aggiorno il database firme (freshclam)..."
if [[ "$INIT_SYS" == "systemd" ]]; then
  systemctl stop clamav-freshclam 2>/dev/null || true
fi
freshclam --quiet || warn "freshclam ha dato un avviso (spesso normale al primo run). Continuo."

# ---------------------------------------------------------------------------
# 5) Individua il/i nomi dell'unita' systemd per clamd (variano per distro)
# ---------------------------------------------------------------------------
CLAMD_SERVICE=""
CLAMD_SOCKET=""
FRESHCLAM_SERVICE=""

if [[ "$INIT_SYS" == "systemd" ]]; then
  systemctl stop clamav-daemon.service clamav-daemon.socket 2>/dev/null || true
  systemctl stop 'clamd@scan.service' 2>/dev/null || true
  systemctl stop clamd.service 2>/dev/null || true

  if systemctl list-unit-files 2>/dev/null | grep -q '^clamav-daemon\.service'; then
    CLAMD_SERVICE="clamav-daemon.service"
    systemctl list-unit-files 2>/dev/null | grep -q '^clamav-daemon\.socket' && CLAMD_SOCKET="clamav-daemon.socket"
  elif [[ -f /etc/clamd.d/scan.conf ]] && systemctl list-unit-files 2>/dev/null | grep -q '^clamd@\.service'; then
    CLAMD_SERVICE="clamd@scan.service"
  elif systemctl list-unit-files 2>/dev/null | grep -q '^clamd\.service'; then
    CLAMD_SERVICE="clamd.service"
  fi

  systemctl list-unit-files 2>/dev/null | grep -q '^clamav-freshclam\.service' && FRESHCLAM_SERVICE="clamav-freshclam.service"

  if [[ -z "$CLAMD_SERVICE" ]]; then
    err "Non riesco a determinare automaticamente il nome dell'unita' systemd per clamd su questa distro."
    err "Unita' disponibili che contengono 'clam':"
    systemctl list-unit-files 2>/dev/null | grep -i clam || true
    err "Segnalami questo elenco cosi' aggiorniamo lo script per la tua distro."
    exit 1
  fi
  log "Unita' clamd: $CLAMD_SERVICE${CLAMD_SOCKET:+ (socket: $CLAMD_SOCKET)}"
fi

# ---------------------------------------------------------------------------
# 6) Se c'e' una socket unit, disattivala e rimuovi la dipendenza Requires=
# ---------------------------------------------------------------------------
if [[ -n "$CLAMD_SOCKET" ]]; then
  log "Trovata $CLAMD_SOCKET: la maschero per evitare che ignori la config TCP..."
  systemctl disable --now "$CLAMD_SOCKET" 2>/dev/null || true
  systemctl mask "$CLAMD_SOCKET"

  log "Rimuovo la dipendenza Requires= di $CLAMD_SERVICE dalla socket unit..."
  OVERRIDE_DIR="/etc/systemd/system/${CLAMD_SERVICE}.d"
  mkdir -p "$OVERRIDE_DIR"
  cat > "${OVERRIDE_DIR}/override.conf" << EOF
[Unit]
Requires=
EOF
  ok "Socket unit mascherata e dipendenza rimossa."
fi

# ---------------------------------------------------------------------------
# 7) Configura clamd.conf: TCPSocket + TCPAddr (idempotente)
# ---------------------------------------------------------------------------
log "Configuro $CLAMD_CONF per l'ascolto TCP..."
cp "$CLAMD_CONF" "${CLAMD_CONF}.bak.$(date +%Y%m%d%H%M%S)"

set_conf_value() {
  local key="$1" value="$2" file="$3"
  if grep -qE "^[#[:space:]]*${key}[[:space:]]" "$file"; then
    sed -i -E "s|^[#[:space:]]*${key}[[:space:]].*|${key} ${value}|" "$file"
  else
    echo "${key} ${value}" >> "$file"
  fi
}

set_conf_value "TCPSocket" "$PORT" "$CLAMD_CONF"
set_conf_value "TCPAddr" "$BIND_ADDR" "$CLAMD_CONF"
ok "TCPSocket = $PORT, TCPAddr = $BIND_ADDR impostati (backup salvato accanto all'originale)."

# ---------------------------------------------------------------------------
# 8) Avvia/abilita i servizi
# ---------------------------------------------------------------------------
log "Avvio clamd..."
if [[ "$INIT_SYS" == "systemd" ]]; then
  systemctl daemon-reload
  systemctl enable --now "$CLAMD_SERVICE"
  [[ -n "$FRESHCLAM_SERVICE" ]] && (systemctl enable --now "$FRESHCLAM_SERVICE" >/dev/null 2>&1 || true)

  sleep 2
  if ! systemctl is-active --quiet "$CLAMD_SERVICE"; then
    err "$CLAMD_SERVICE non e' partito correttamente. Ultime righe di log:"
    journalctl -u "$CLAMD_SERVICE" -n 30 --no-pager
    exit 1
  fi
  ok "$CLAMD_SERVICE e' attivo."
else
  rc-update add clamd default 2>/dev/null || true
  rc-service clamd restart
  rc-update add freshclamd default 2>/dev/null || true
  rc-service freshclamd restart 2>/dev/null || true
  sleep 2
  if ! rc-service clamd status | grep -qi started; then
    err "Il servizio clamd (OpenRC) non risulta avviato. Controlla: rc-service clamd status"
    exit 1
  fi
  ok "clamd (OpenRC) e' attivo."
fi

# ---------------------------------------------------------------------------
# 9) Firewall (best-effort, solo se rilevo ufw o firewalld)
# ---------------------------------------------------------------------------
if [[ -n "$CONSOLE_IP" ]]; then
  if command -v ufw >/dev/null 2>&1; then
    log "Apro la porta $PORT su ufw, solo per $CONSOLE_IP..."
    ufw allow from "$CONSOLE_IP" to any port "$PORT" proto tcp
    ok "Regola ufw aggiunta."
  elif command -v firewall-cmd >/dev/null 2>&1; then
    log "Apro la porta $PORT su firewalld, solo per $CONSOLE_IP..."
    firewall-cmd --permanent --add-rich-rule="rule family='ipv4' source address='${CONSOLE_IP}/32' port protocol='tcp' port='${PORT}' accept"
    firewall-cmd --reload
    ok "Regola firewalld aggiunta."
  else
    warn "Nessun firewall gestito (ufw/firewalld) rilevato: se ne usi un altro (iptables/nftables), apri la porta $PORT manualmente per $CONSOLE_IP."
  fi
else
  warn "Nessun --console-ip fornito: non ho toccato alcun firewall. Se e' attivo, apri manualmente la porta $PORT per l'IP della console."
fi

# ---------------------------------------------------------------------------
# 10) Verifica finale
# ---------------------------------------------------------------------------
log "Verifico che la porta $PORT sia in ascolto..."
if command -v ss >/dev/null 2>&1; then
  LISTEN_LINE=$(ss -tulpn 2>/dev/null | grep ":$PORT " || true)
else
  LISTEN_LINE=$(netstat -tulpn 2>/dev/null | grep ":$PORT " || true)
fi

if [[ -n "$LISTEN_LINE" ]]; then
  ok "clamd e' in ascolto sulla porta $PORT:"
  echo "    $LISTEN_LINE"
else
  err "La porta $PORT non risulta in ascolto."
  if [[ "$INIT_SYS" == "systemd" ]]; then
    echo "    sudo systemctl status $CLAMD_SERVICE"
    echo "    sudo journalctl -u $CLAMD_SERVICE -n 50"
  else
    echo "    rc-service clamd status"
  fi
  exit 1
fi

log "Test PING al demone locale..."
if command -v nc >/dev/null 2>&1; then
  PING_RESPONSE=$(echo -e "PING" | nc -q1 127.0.0.1 "$PORT" 2>/dev/null || true)
  if [[ "$PING_RESPONSE" == *"PONG"* ]]; then
    ok "clamd risponde correttamente (PONG ricevuto)."
  else
    warn "Non ho ricevuto PONG (risposta: '$PING_RESPONSE'). Porta aperta ma vale la pena controllare i log se dalla console non funziona."
  fi
else
  warn "netcat non installato, salto il test PING."
fi

echo
ok "Fatto. Nella console web, aggiungi questo endpoint da Admin > Endpoints:"
echo "    Host: $(hostname -I 2>/dev/null | awk '{print $1}') (o l'IP corretto di questa macchina)"
echo "    Port: $PORT"
echo "    Platform: UNIX"
