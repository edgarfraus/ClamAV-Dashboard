# Agent ClaimAV - script di installazione generato dalla console.
#   Endpoint: @@ENDPOINT_NAME@@
#   Console:  @@CONSOLE_URL@@
#
# La chiave vale SOLO per questo endpoint: consente di scaricare l'agent e di
# inviare i report, nient'altro. Se trapela, rigenerala dalla console
# (Admin > Endpoints > Rotate) e reinstalla.
#
# USO (PowerShell come Amministratore):
#   irm '@@CONSOLE_URL@@/agent/install.ps1?key=@@AGENT_KEY@@' | iex
#
# NOTA: su Windows la protezione realtime di ClamAV non esiste (l'on-access usa
# fanotify, che e' solo Linux). Qui viene installata una scansione programmata
# che invia i risultati alla console.

$ErrorActionPreference = 'Stop'

$ConsoleUrl   = '@@CONSOLE_URL@@'
$AgentKey     = '@@AGENT_KEY@@'
$EndpointName = '@@ENDPOINT_NAME@@'

$InstallDir = Join-Path $env:ProgramData 'ClaimAV'
$ConfigPath = Join-Path $InstallDir 'agent.conf.json'
$ScanScript = Join-Path $InstallDir 'scan-report.ps1'
$PollScript = Join-Path $InstallDir 'poll-agent.ps1'
$TaskName   = 'ClaimAV Agent Scan'
$PollTask   = 'ClaimAV Agent Poll'

# Percorsi scansionati dal task programmato. Modificali qui se serve.
$ScanPaths = @("$env:SystemDrive\Users", "$env:SystemDrive\ProgramData")

function Write-Info  { param($m) Write-Host "[*] $m"  -ForegroundColor Cyan }
function Write-Ok    { param($m) Write-Host "[OK] $m" -ForegroundColor Green }
function Write-Warn  { param($m) Write-Host "[!] $m"  -ForegroundColor Yellow }
function Write-Err   { param($m) Write-Host "[ERRORE] $m" -ForegroundColor Red }

# --- 1) Richiede privilegi di amministratore -------------------------------
$identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Err "Esegui questo script in una PowerShell aperta come Amministratore."
    exit 1
}

# --- 2) Verifica che ClamAV sia presente -----------------------------------
# Non tento di scaricare un MSI con una versione fissa nell'URL: quel link si
# rompe a ogni release. Se ClamAV manca, lo dico e mi fermo.
$clamScan = $null
foreach ($candidate in @(
    "$env:ProgramFiles\ClamAV\clamdscan.exe",
    "$env:ProgramFiles\ClamAV\clamscan.exe",
    "${env:ProgramFiles(x86)}\ClamAV\clamdscan.exe",
    "${env:ProgramFiles(x86)}\ClamAV\clamscan.exe"
)) {
    if (Test-Path $candidate) { $clamScan = $candidate; break }
}
if (-not $clamScan) {
    $cmd = Get-Command clamdscan.exe, clamscan.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { $clamScan = $cmd.Source }
}
if (-not $clamScan) {
    Write-Err "ClamAV non risulta installato su questa macchina."
    Write-Err "Scarica il pacchetto Windows da https://www.clamav.net/downloads ,"
    Write-Err "installalo, esegui 'freshclam' almeno una volta, poi rilancia questo script."
    exit 1
}
Write-Ok "ClamAV trovato: $clamScan"

# --- 3) Configurazione (URL + chiave) --------------------------------------
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

@{
    ConsoleUrl = $ConsoleUrl
    AgentKey   = $AgentKey
    ScanPaths  = $ScanPaths
    ClamScan   = $clamScan
} | ConvertTo-Json | Set-Content -Path $ConfigPath -Encoding UTF8

# La chiave e' un segreto: solo SYSTEM e Administrators devono poterla leggere.
$acl = Get-Acl $ConfigPath
$acl.SetAccessRuleProtection($true, $false)
$acl.Access | ForEach-Object { $acl.RemoveAccessRule($_) | Out-Null }
foreach ($who in @('NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators')) {
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
        $who, 'FullControl', 'Allow'))) | Out-Null
}
Set-Acl -Path $ConfigPath -AclObject $acl
Write-Ok "Configurazione salvata in $ConfigPath (leggibile solo da Administrators/SYSTEM)."

# --- 4) Script di scansione + invio dei risultati ---------------------------
$scanScriptBody = @'
$ErrorActionPreference = 'Stop'
$cfg = Get-Content (Join-Path $env:ProgramData 'ClaimAV\agent.conf.json') -Raw | ConvertFrom-Json

$findings = New-Object System.Collections.Generic.List[string]
$verdict  = 'OK'
$errorMsg = ''

try {
    # --infected: stampa solo i file rilevati. L'output e' "<path>: <FIRMA> FOUND",
    # lo stesso formato che l'endpoint /api/scan/report sa gia' interpretare.
    $output = & $cfg.ClamScan --infected $cfg.ScanPaths 2>&1
    $exit = $LASTEXITCODE
    foreach ($line in $output) {
        if ($line -match ' FOUND$') { $findings.Add([string]$line) }
    }
    if ($exit -eq 1) {
        $verdict = 'VIRUS_FOUND'
    } elseif ($exit -ne 0) {
        $verdict  = 'ERROR'
        $errorMsg = "clamscan exit $exit`n" + ($output -join "`n")
    }
} catch {
    $verdict  = 'ERROR'
    $errorMsg = $_.Exception.Message
}

# Come per gli altri agent, si segnala solo cio' che conta: una scansione pulita
# non deve riempire la lista dei job.
if ($verdict -eq 'OK') { exit 0 }

$payload = @{
    hostname     = $env:COMPUTERNAME
    path         = ($cfg.ScanPaths -join ' ')
    verdict      = $verdict
    source       = 'batch'
    findings     = @($findings)
    errorMessage = $errorMsg
} | ConvertTo-Json -Depth 4

Invoke-RestMethod -Method Post -Uri ($cfg.ConsoleUrl.TrimEnd('/') + '/api/scan/report') `
    -Headers @{ 'X-Agent-Key' = $cfg.AgentKey } `
    -ContentType 'application/json' -Body $payload | Out-Null
'@
Set-Content -Path $ScanScript -Value $scanScriptBody -Encoding UTF8
Write-Ok "Script di scansione installato in $ScanScript"

# --- 5) Task programmato ----------------------------------------------------
$action    = New-ScheduledTaskAction -Execute 'powershell.exe' `
                -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$ScanScript`""
$trigger   = New-ScheduledTaskTrigger -Daily -At 2:30AM
$principalTask = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings  = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopOnIdleEnd

Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Principal $principalTask -Settings $settings | Out-Null
Write-Ok "Task programmato '$TaskName' registrato (ogni notte alle 02:30)."

# --- 5b) Agent: esegue le scansioni richieste dalla console -----------------
# E' quello che fa funzionare il pulsante "Scan" della console per questa
# macchina: la console accoda, l'agent ritira ed esegue in locale.
$pollScriptBody = @'
$ErrorActionPreference = 'Stop'
$cfg = Get-Content (Join-Path $env:ProgramData 'ClaimAVgent.conf.json') -Raw | ConvertFrom-Json
$base = $cfg.ConsoleUrl.TrimEnd('/')
$headers = @{ 'X-Agent-Key' = $cfg.AgentKey }

try {
    $resp = Invoke-RestMethod -Uri "$base/api/agent/commands" -Headers $headers -TimeoutSec 15
} catch {
    # Console irraggiungibile: non e' un errore da segnalare, si riprova al prossimo giro.
    exit 0
}

foreach ($cmd in $resp.commands) {
    $targets = @($cmd.target -split "`n" | Where-Object { $_.Trim() -ne '' })
    $verdict  = 'OK'
    $errorMsg = ''
    $findings = New-Object System.Collections.Generic.List[string]

    try {
        $output = & $cfg.ClamScan --recursive --infected $targets 2>&1
        $exit = $LASTEXITCODE
        foreach ($line in $output) {
            if ($line -match ' FOUND$') { $findings.Add([string]$line) }
        }
        if ($findings.Count -gt 0) {
            $verdict = 'VIRUS_FOUND'
        } elseif ($exit -ne 0) {
            $verdict  = 'ERROR'
            $errorMsg = "clamscan exit $exit`n" + ($output -join "`n")
        }
    } catch {
        $verdict  = 'ERROR'
        $errorMsg = $_.Exception.Message
    }

    $payload = @{
        hostname     = $env:COMPUTERNAME
        path         = ($targets -join ' ')
        verdict      = $verdict
        commandId    = $cmd.id
        findings     = @($findings)
        errorMessage = $errorMsg
    } | ConvertTo-Json -Depth 4

    try {
        Invoke-RestMethod -Method Post -Uri "$base/api/scan/report" -Headers $headers `
            -ContentType 'application/json' -Body $payload -TimeoutSec 30 | Out-Null
    } catch {
        # La console chiudera' il comando per timeout: meglio che bloccare il ciclo.
    }
}
'@
Set-Content -Path $PollScript -Value $pollScriptBody -Encoding UTF8
Write-Ok "Agent installato in $PollScript"

$pollAction  = New-ScheduledTaskAction -Execute 'powershell.exe' `
                  -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$PollScript`""
$pollTrigger = New-ScheduledTaskTrigger -Once -At (Get-Date) `
                  -RepetitionInterval (New-TimeSpan -Minutes 5) `
                  -RepetitionDuration (New-TimeSpan -Days 3650)
Unregister-ScheduledTask -TaskName $PollTask -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $PollTask -Action $pollAction -Trigger $pollTrigger `
    -Principal $principalTask -Settings $settings | Out-Null
Write-Ok "Task '$PollTask' registrato (controlla la console ogni 5 minuti)."

# --- 6) Verifica della connessione alla console -----------------------------
Write-Info "Verifico che la console accetti i report da questa macchina..."
try {
    $testPayload = @{
        hostname     = $env:COMPUTERNAME
        path         = 'installazione agent'
        verdict      = 'ERROR'
        source       = 'batch'
        errorMessage = "Test di connettivita' dell'agent Windows: console e chiave funzionano."
    } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri ($ConsoleUrl.TrimEnd('/') + '/api/scan/report') `
        -Headers @{ 'X-Agent-Key' = $AgentKey } `
        -ContentType 'application/json' -Body $testPayload | Out-Null
    Write-Ok "Console raggiungibile: cerca il job di test nella pagina Jobs."
} catch {
    Write-Warn "Test fallito: $($_.Exception.Message)"
    Write-Warn "Controlla URL della console, chiave e connettivita' di rete."
}

Write-Host ''
Write-Ok "Agent '$EndpointName' installato."
Write-Warn "Ricorda: su Windows non c'e' protezione realtime ClamAV."
Write-Host "Le scansioni lanciate dalla console vengono ritirate entro 5 minuti." -ForegroundColor Cyan
