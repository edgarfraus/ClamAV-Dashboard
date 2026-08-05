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
#   --scan-system         Configura clamd per scansionare l'INTERA macchina
#                          (User root + boolean SELinux antivirus_can_scan_system).
#                          ATTENZIONE: clamd leggera' qualunque file come root; usa
#                          SEMPRE --console-ip per limitare l'accesso alla porta.
#   -h, --help             Mostra questo aiuto
#
# ESEMPI:
#   sudo ./install-clamd-remote.sh --console-ip 192.168.1.50
#   sudo ./install-clamd-remote.sh --scan-system --console-ip 192.168.1.50
#
set -euo pipefail

PORT=3310
BIND_ADDR="0.0.0.0"
CONSOLE_IP=""
SKIP_INSTALL=0
SCAN_SYSTEM=0

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
    --scan-system) SCAN_SYSTEM=1; shift ;;
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
freshclam --stdout || warn "freshclam ha dato un avviso (spesso normale al primo run, es. database gia' aggiornato). Continuo."

# Il servizio clamd (via systemd socket activation) su alcune distro parte solo
# se il database firme e' gia' presente su disco (ConditionPathExistsGlob).
# Se freshclam non ha ancora finito di scaricarlo, aspettiamo qui, altrimenti
# la socket unit viene "skippata" e clamd puo' finire per legarsi alla porta
# per conto suo, causando poi un conflitto quando la socket riprova piu' tardi.
log "Verifico che il database firme sia presente su disco..."
db_present() {
  [[ -f /var/lib/clamav/daily.cvd || -f /var/lib/clamav/daily.cld ]] || \
  [[ -f /var/lib/clamav/main.cvd  || -f /var/lib/clamav/main.cld  ]]
}
DB_WAIT=0
while ! db_present; do
  if [[ $DB_WAIT -ge 120 ]]; then
    warn "Il database firme non risulta ancora presente dopo 2 minuti di attesa."
    warn "Provo un altro giro di freshclam per capire l'errore:"
    freshclam --stdout || true
    break
  fi
  sleep 5
  DB_WAIT=$((DB_WAIT + 5))
  log "  ...ancora in attesa del database firme (${DB_WAIT}s)"
done
ok "Database firme pronto (o ho proceduto comunque dopo l'attesa massima)."

# ---------------------------------------------------------------------------
# 5) Individua il/i nomi dell'unita' systemd per clamd (variano per distro)
# ---------------------------------------------------------------------------
CLAMD_SERVICE=""
CLAMD_SOCKET=""
FRESHCLAM_SERVICE=""

# Controllo robusto dell'esistenza di una unit, indipendente dal formato
# tabellare di "systemctl list-unit-files" (che varia tra versioni/distro).
unit_exists() {
  local state
  state=$(systemctl show -p LoadState --value "$1" 2>/dev/null || echo "not-found")
  [[ "$state" == "loaded" || "$state" == "masked" ]]
}

# Verifica l'esistenza di un TEMPLATE unit (es. clamd@.service).
# Per un template "nudo" (senza istanza) systemctl show riporta LoadState=stub,
# quindi unit_exists() lo considererebbe assente: usiamo "systemctl cat", che
# invece restituisce il contenuto del template se esiste.
template_exists() {
  systemctl cat "$1" >/dev/null 2>&1
}

if [[ "$INIT_SYS" == "systemd" ]]; then
  # Le distro RHEL/Fedora usano un TEMPLATE unit clamd@.service, che va
  # istanziato col nome del file di config: /etc/clamd.d/scan.conf -> clamd@scan.
  # Ricaviamo il nome dell'istanza dal config gia' rilevato (niente hardcode).
  CLAMD_INSTANCE=""
  case "$CLAMD_CONF" in
    /etc/clamd.d/*.conf) CLAMD_INSTANCE="$(basename "$CLAMD_CONF" .conf)" ;;
  esac

  systemctl stop clamav-daemon.service clamav-daemon.socket 2>/dev/null || true
  [[ -n "$CLAMD_INSTANCE" ]] && systemctl stop "clamd@${CLAMD_INSTANCE}.service" 2>/dev/null || true
  systemctl stop clamd.service 2>/dev/null || true

  if unit_exists clamav-daemon.service; then
    CLAMD_SERVICE="clamav-daemon.service"
    unit_exists clamav-daemon.socket && CLAMD_SOCKET="clamav-daemon.socket"
  elif [[ -n "$CLAMD_INSTANCE" ]] && template_exists 'clamd@.service'; then
    CLAMD_SERVICE="clamd@${CLAMD_INSTANCE}.service"
  elif unit_exists clamd.service; then
    CLAMD_SERVICE="clamd.service"
  fi

  unit_exists clamav-freshclam.service && FRESHCLAM_SERVICE="clamav-freshclam.service"

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
# 6) Configura clamd.conf: TCPSocket + TCPAddr (idempotente)
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

# Fedora/RHEL: il config di default (/etc/clamd.d/scan.conf) contiene una riga
# "Example" che fa rifiutare l'avvio a clamd ("Please edit the example config
# file") finche' non viene rimossa/commentata. Idempotente: agisce solo se
# esiste una riga "Example" isolata.
if grep -qE '^[[:space:]]*Example[[:space:]]*$' "$CLAMD_CONF"; then
  sed -i -E 's|^[[:space:]]*Example[[:space:]]*$|# Example (commentata da install-clamd-remote.sh)|' "$CLAMD_CONF"
  ok "Riga 'Example' commentata in $CLAMD_CONF (richiesta su Fedora/RHEL per avviare clamd)."
fi

set_conf_value "TCPSocket" "$PORT" "$CLAMD_CONF"
set_conf_value "TCPAddr" "$BIND_ADDR" "$CLAMD_CONF"
ok "TCPSocket = $PORT, TCPAddr = $BIND_ADDR impostati (backup salvato accanto all'originale)."

# --scan-system: abilita la scansione dell'INTERO filesystem via scansioni PATH.
# Su TCP il fd-pass non e' disponibile, quindi e' clamd stesso ad aprire i file:
# per leggere qualunque path deve girare come root E, su sistemi SELinux, avere
# il boolean antivirus_can_scan_system attivo. Senza entrambe, i path fuori dai
# contesti di clamd falliscono con "Permission denied".
if [[ "$SCAN_SYSTEM" -eq 1 ]]; then
  log "Modalita' --scan-system: configuro clamd per leggere l'intero filesystem..."
  set_conf_value "User" "root" "$CLAMD_CONF"
  if command -v getenforce >/dev/null 2>&1 && [[ "$(getenforce 2>/dev/null)" != "Disabled" ]]; then
    if command -v setsebool >/dev/null 2>&1; then
      if setsebool -P antivirus_can_scan_system 1 2>/dev/null; then
        ok "SELinux: boolean antivirus_can_scan_system abilitato (persistente)."
      else
        warn "Non sono riuscito a impostare il boolean SELinux antivirus_can_scan_system: se le scansioni di path di sistema falliscono, eseguilo a mano."
      fi
    fi
  fi
  warn "clamd girera' come ROOT e leggera' qualunque file: assicurati di aver limitato la porta $PORT (usa --console-ip)."
  ok "Scansione intero sistema configurata (User=root${INIT_SYS:+, SELinux gestito se presente})."
fi

# ---------------------------------------------------------------------------
# 7) Socket unit: alcune distro (es. Ubuntu recenti, tramite
#    clamav-daemon-socket-generator) rigenerano AUTOMATICAMENTE il
#    ListenStream della .socket leggendo TCPSocket/TCPAddr da clamd.conf,
#    ogni volta che si fa "daemon-reload". In quel caso NON dobbiamo
#    aggiungere un nostro drop-in, altrimenti si duplica il bind sulla
#    stessa porta e la socket unit fallisce con "Address already in use"
#    (conflitto con se stessa). Su distro piu' vecchie (es. Debian
#    bookworm senza generator) serve invece il drop-in manuale, che
#    aggiungiamo solo come fallback se il generator non ha gia' fatto
#    il lavoro.
# ---------------------------------------------------------------------------
if [[ -n "$CLAMD_SOCKET" ]]; then
  # Rimuovo qualunque drop-in manuale lasciato da un run precedente di
  # QUESTO script, cosi' ripartiamo puliti prima di ridecidere se serve.
  rm -rf "/etc/systemd/system/${CLAMD_SOCKET}.d" 2>/dev/null || true
  rm -f "/etc/systemd/system/${CLAMD_SERVICE}.d/override.conf" 2>/dev/null || true

  # Se un run precedente aveva mascherato la socket, la sblocco.
  systemctl unmask "$CLAMD_SOCKET" 2>/dev/null || true

  systemctl daemon-reload

  if [[ "$BIND_ADDR" == "0.0.0.0" || -z "$BIND_ADDR" ]]; then
    LISTEN_VALUE="$PORT"
  else
    LISTEN_VALUE="${BIND_ADDR}:${PORT}"
  fi

  ALREADY_CONFIGURED=0
  if systemctl cat "$CLAMD_SOCKET" 2>/dev/null | grep -qE "ListenStream=($BIND_ADDR:)?$PORT$|ListenStream=0\.0\.0\.0:$PORT$"; then
    ALREADY_CONFIGURED=1
  fi

  if [[ $ALREADY_CONFIGURED -eq 1 ]]; then
    ok "$CLAMD_SOCKET ascolta gia' sulla porta $PORT (generata automaticamente da questa distro, da clamd.conf). Nessun drop-in manuale necessario."
  else
    log "$CLAMD_SOCKET non ha ancora un listener TCP: aggiungo un drop-in manuale (fallback)..."
    SOCKET_OVERRIDE_DIR="/etc/systemd/system/${CLAMD_SOCKET}.d"
    mkdir -p "$SOCKET_OVERRIDE_DIR"
    cat > "${SOCKET_OVERRIDE_DIR}/tcp-socket.conf" << EOF
[Socket]
ListenStream=${LISTEN_VALUE}
EOF
    systemctl daemon-reload
    ok "Listener TCP aggiunto manualmente a $CLAMD_SOCKET (ListenStream=${LISTEN_VALUE})."
  fi
fi

# ---------------------------------------------------------------------------
# 8) Avvia/abilita i servizi
# ---------------------------------------------------------------------------
log "Avvio clamd..."
if [[ "$INIT_SYS" == "systemd" ]]; then
  systemctl daemon-reload

  # Libero eventuali processi residui gia' legati alla porta (es. da un run
  # precedente dove clamd si era legato direttamente saltando systemd).
  if command -v fuser >/dev/null 2>&1; then
    fuser -k "${PORT}/tcp" 2>/dev/null || true
    sleep 1
  fi

  if [[ -n "$CLAMD_SOCKET" ]]; then
    systemctl enable --now "$CLAMD_SOCKET"
  fi
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
