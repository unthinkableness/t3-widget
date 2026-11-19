<#
.SYNOPSIS
  Pairs the Android T3 widget app with this PC by QR code.

.DESCRIPTION
  1. Mints a read-only bearer token (orchestration:read + relay:read) with the T3 CLI.
  2. Discovers the T3 Connect tunnel URL and the LAN URL, and checks that both accept the token.
  3. Builds  t3widget://setup#v=1&url=...&lan=...&token=...
  4. Shows it as a QR code in a temporary HTML page (deleted afterwards), or copies it to the
     clipboard with -CopyLink.
  The token is never printed, logged or written to a persistent file.

.PARAMETER Ttl
  Token lifetime, e.g. 365d, 90d, 12h. Default 365d.

.PARAMETER Label
  Session label shown in `t3 auth session list`. Default "Android widget".

.PARAMETER RevokeOld
  Revoke existing sessions with the same label without asking.

.PARAMETER KeepOld
  Keep existing sessions with the same label without asking.

.PARAMETER CopyLink
  Put the setup link on the clipboard instead of showing a QR code.

.PARAMETER CloseAfter
  Seconds before the QR page file is deleted when nobody presses Enter. Default 180.

.EXAMPLE
  .\setup-widget.ps1
.EXAMPLE
  .\setup-widget.ps1 -RevokeOld -Ttl 90d
#>
[CmdletBinding()]
param(
  [string]$Ttl = "365d",
  [string]$Label = "Android widget",
  [switch]$RevokeOld,
  [switch]$KeepOld,
  [switch]$CopyLink,
  [ValidateRange(5, 3600)][int]$CloseAfter = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$T3Cli       = Join-Path $env:USERPROFILE ".t3\bin\t3.cmd"
$RuntimeFile = Join-Path $env:USERPROFILE ".t3\userdata\server-runtime.json"
$ToolsDir    = Join-Path $PSScriptRoot "tools"
$Interactive = -not [Console]::IsInputRedirected

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    [ok]   $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "    [warn] $msg" -ForegroundColor Yellow }
function Write-Bad($msg)  { Write-Host "    [fail] $msg" -ForegroundColor Red }

function Invoke-T3 {
  # Runs the T3 CLI and returns stdout as one string. Throws on a non-zero exit.
  param([Parameter(ValueFromRemainingArguments)][string[]]$CliArgs)
  # PS 5.1 turns native stderr into terminating errors under EAP=Stop, and the T3 CLI
  # (Electron) sometimes writes harmless noise there; rely on the exit code instead.
  $ErrorActionPreference = "Continue"
  $out = & $T3Cli @CliArgs 2>$null | Out-String
  if ($LASTEXITCODE -ne 0) { throw "t3 $($CliArgs[0..2] -join ' ') failed (exit $LASTEXITCODE)." }
  return $out
}

function Test-Endpoint {
  # Returns $true when <base>/api/auth/session says authenticated:true for the token.
  param([string]$Base, [string]$Token)
  try {
    $r = Invoke-WebRequest -Uri "$Base/api/auth/session" -Headers @{ Authorization = "Bearer $Token" } `
           -UseBasicParsing -TimeoutSec 10
    if ($r.Headers["Content-Type"] -notmatch "json") { return @{ ok = $false; why = "non-JSON reply" } }
    $j = $r.Content | ConvertFrom-Json
    if ($j.authenticated -eq $true) { return @{ ok = $true; why = "authenticated" } }
    return @{ ok = $false; why = "authenticated=false" }
  } catch {
    $code = $null
    try { $code = [int]$_.Exception.Response.StatusCode } catch {}
    if ($code) { return @{ ok = $false; why = "HTTP $code" } }
    return @{ ok = $false; why = ($_.Exception.Message -split "`n")[0] }
  }
}

function Get-LanIPv4 {
  # IPv4 of the active physical Wi-Fi/Ethernet adapter with a default gateway.
  $cfgs = Get-NetIPConfiguration -ErrorAction SilentlyContinue |
    Where-Object {
      $_.IPv4DefaultGateway -and $_.NetAdapter -and $_.NetAdapter.Status -eq "Up" -and
      $_.InterfaceAlias -notmatch "vEthernet|WSL|Hyper-V|VirtualBox|VMware|Loopback|Tailscale|ZeroTier|VPN|TAP" -and
      $_.InterfaceDescription -notmatch "Hyper-V|Virtual|VPN|TAP-|Wintun|WireGuard"
    }
  foreach ($c in $cfgs) {
    foreach ($a in @($c.IPv4Address)) {
      if ($a.IPAddress -and $a.IPAddress -notlike "169.254.*" -and $a.IPAddress -ne "127.0.0.1") {
        return $a.IPAddress
      }
    }
  }
  return $null
}

# ---------------------------------------------------------------- preflight
if (-not (Test-Path $T3Cli)) { throw "T3 CLI not found at $T3Cli." }
if (-not (Test-Path $RuntimeFile)) { throw "T3 server runtime file not found ($RuntimeFile). Is T3 Code running?" }
$runtime = Get-Content $RuntimeFile -Raw | ConvertFrom-Json
$Port = [int]$runtime.port
$LocalBase = "http://127.0.0.1:$Port"

if (-not $CopyLink) {
  if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw "Node.js is required for the QR code (or use -CopyLink)." }
  if (-not (Test-Path (Join-Path $ToolsDir "node_modules\qrcode"))) {
    Write-Step "Installing QR tools into $ToolsDir (one-time, local only)"
    Push-Location $ToolsDir
    try { & npm install --no-fund --no-audit --loglevel=error | Out-Null; if ($LASTEXITCODE -ne 0) { throw "npm install failed." } }
    finally { Pop-Location }
  }
}

# ---------------------------------------------------------------- old sessions
Write-Step "Checking existing sessions labelled '$Label'"
# PS 5.1's ConvertFrom-Json emits a JSON array as ONE object; assign first so the pipe below enumerates it.
$sessions = Invoke-T3 auth session list --json | ConvertFrom-Json
$old = @($sessions | Where-Object { $_.client -and $_.client.PSObject.Properties["label"] -and $_.client.label -eq $Label })
if ($old.Count -eq 0) {
  Write-Ok "none found"
} else {
  foreach ($s in $old) { Write-Host "    - $($s.sessionId)  issued $($s.issuedAt)  expires $($s.expiresAt)" }
  $doRevoke = $false
  if ($RevokeOld) { $doRevoke = $true }
  elseif ($KeepOld) { $doRevoke = $false }
  elseif ($Interactive) {
    $ans = Read-Host "    Revoke these $($old.Count) old session(s)? Phones still using them will need re-pairing [y/N]"
    $doRevoke = $ans -match '^(y|yes)$'
  } else {
    Write-Warn2 "non-interactive: keeping them (pass -RevokeOld to revoke)"
  }
  if ($doRevoke) {
    foreach ($s in $old) { Invoke-T3 auth session revoke $s.sessionId | Out-Null; Write-Ok "revoked $($s.sessionId)" }
  }
}

# ---------------------------------------------------------------- mint token
Write-Step "Minting read-only token (orchestration:read, relay:read, ttl $Ttl)"
# --json (instead of --token-only) also returns the session id and expiry; the token stays in memory.
$issued = Invoke-T3 auth session issue --scope orchestration:read --scope relay:read --ttl $Ttl --label $Label --json | ConvertFrom-Json
$Token = [string]$issued.token
$SessionId = [string]$issued.sessionId
$ExpiresAt = [string]$issued.expiresAt
if (-not $Token) { throw "t3 auth session issue returned no token." }
Write-Ok "session $SessionId, expires $ExpiresAt"

$setupOk = $false
try {
  # ------------------------------------------------------------ endpoints
  Write-Step "Discovering endpoints"
  $TunnelBase = $null
  try {
    $ls = Invoke-RestMethod -Uri "$LocalBase/api/connect/link-state" -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 10
    if ($ls.PSObject.Properties["tunnelHostname"] -and $ls.tunnelHostname) { $TunnelBase = "https://$($ls.tunnelHostname)" }
    if (-not $ls.linked) { Write-Warn2 "T3 Connect is not linked on this PC; the tunnel may not work." }
    $reach = if ($ls.PSObject.Properties["reachability"] -and $ls.reachability) { $ls.reachability.status } else { "unknown" }
    if ($reach -ne "reachable") { Write-Warn2 "tunnel reachability is '$reach' (expected 'reachable')." }
  } catch {
    Write-Warn2 "could not read link-state: $(($_.Exception.Message -split "`n")[0])"
  }
  $lanIp = Get-LanIPv4
  $LanBase = if ($lanIp) { "http://${lanIp}:$Port" } else { $null }
  if (-not $LanBase) { Write-Warn2 "no LAN IPv4 found on an active Wi-Fi/Ethernet adapter." }

  $results = [ordered]@{}
  if ($TunnelBase) { $results["Tunnel"] = @{ base = $TunnelBase } + (Test-Endpoint $TunnelBase $Token) }
  if ($LanBase)    { $results["LAN"]    = @{ base = $LanBase }    + (Test-Endpoint $LanBase $Token) }
  foreach ($k in $results.Keys) {
    $r = $results[$k]
    if ($r.ok) { Write-Ok "$k  $($r.base)  ($($r.why))" } else { Write-Bad "$k  $($r.base)  ($($r.why))" }
  }
  if (-not $TunnelBase -and -not $LanBase) { throw "No endpoint found (no tunnel, no LAN address)." }
  if (-not ($results.Values | Where-Object { $_.ok })) { throw "Neither endpoint accepted the new token." }

  # ------------------------------------------------------------ link
  $primary = $TunnelBase
  $lanParam = $LanBase
  if (-not $primary) {
    Write-Warn2 "no tunnel: using the LAN URL as the main URL; the widget will only work on this network."
    $primary = $LanBase; $lanParam = $null
  }
  $enc = { param($s) [Uri]::EscapeDataString($s) }
  $link = "t3widget://setup#v=1&url=$(& $enc $primary)"
  if ($lanParam) { $link += "&lan=$(& $enc $lanParam)" }
  $link += "&token=$(& $enc $Token)"

  # ------------------------------------------------------------ deliver
  if ($CopyLink) {
    Set-Clipboard -Value $link
    Write-Step "Setup link copied to the clipboard"
    Write-Warn2 "it contains the token: paste it on the phone, then clear the clipboard (and Win+V history if enabled)."
    if ($Interactive) { Read-Host "    Press Enter to clear the clipboard" | Out-Null }
    else { Write-Host "    clearing the clipboard in $CloseAfter s..."; Start-Sleep -Seconds $CloseAfter }
    Set-Clipboard -Value " "
    Write-Ok "clipboard cleared"
  } else {
    Write-Step "Rendering QR code"
    $htmlPath = Join-Path ([IO.Path]::GetTempPath()) ("t3widget-qr-" + [guid]::NewGuid().ToString("N") + ".html")
    $info = @("Label: $Label", "Expires: $ExpiresAt")
    foreach ($k in $results.Keys) { $info += "${k}: $($results[$k].base) - $(if ($results[$k].ok) {'works'} else {'not reachable from this PC'})" }
    $nodeArgs = @((Join-Path $ToolsDir "qr.mjs"), $htmlPath, "--title", "Pair T3 widget")
    foreach ($l in $info) { $nodeArgs += @("--info", $l) }
    try {
      $qrOut = $link | & node @nodeArgs
      if ($LASTEXITCODE -ne 0) { throw "QR helper failed (exit $LASTEXITCODE)." }
      $qr = ($qrOut | Select-Object -Last 1) | ConvertFrom-Json
      if ($qr.decoded) {
        Write-Verbose ("decoded QR: scheme={0} v={1} url={2} lan={3} keys={4} tokenLength={5}" -f `
          $qr.decoded.scheme, $qr.decoded.v, $qr.decoded.url, $qr.decoded.lan, ($qr.decoded.keys -join ","), $qr.decoded.tokenLength)
      }
      if ($qr.formatOk -and $qr.decodeMatches) { Write-Ok "QR self-check: decoded content matches the setup link" }
      else { throw "QR self-check failed (formatOk=$($qr.formatOk), decodeMatches=$($qr.decodeMatches))." }

      Start-Process $htmlPath
      Write-Ok "opened in your browser - scan it with the phone camera"
      if ($Interactive) {
        Write-Host "    Press Enter once the phone is paired (the page file is deleted after $CloseAfter s regardless)..."
        $deadline = (Get-Date).AddSeconds($CloseAfter)
        while ((Get-Date) -lt $deadline) {
          if ([Console]::KeyAvailable) { $key = [Console]::ReadKey($true); if ($key.Key -eq "Enter") { break } }
          Start-Sleep -Milliseconds 200
        }
      } else {
        Start-Sleep -Seconds $CloseAfter
      }
    } finally {
      if (Test-Path $htmlPath) { Remove-Item $htmlPath -Force -ErrorAction SilentlyContinue }
      if (Test-Path $htmlPath) { Write-Warn2 "could not delete $htmlPath - delete it manually." } else { Write-Ok "temporary QR page deleted" }
    }
  }
  $setupOk = $true
} finally {
  if (-not $setupOk) {
    Write-Warn2 "setup did not finish - revoking the new session $SessionId"
    try { Invoke-T3 auth session revoke $SessionId | Out-Null } catch { Write-Bad "revoke failed: run  t3 auth session revoke $SessionId" }
  }
  $Token = $null; $link = $null
}

# ---------------------------------------------------------------- summary
Write-Host ""
Write-Host "T3 widget pairing ready" -ForegroundColor Green
Write-Host "  Session   : $Label  ($SessionId)"
Write-Host "  Scopes    : orchestration:read, relay:read"
Write-Host "  Expires   : $ExpiresAt"
foreach ($k in $results.Keys) {
  $r = $results[$k]
  Write-Host ("  {0,-10}: {1}  {2}" -f $k, $r.base, $(if ($r.ok) { "works" } else { "FAILED ($($r.why))" }))
}
if ($LanBase) { Write-Host "  (LAN check runs from this PC; if the phone can't reach it, allow TCP $Port in Windows Firewall for private networks.)" }
Write-Host "  Revoke    : $T3Cli auth session revoke $SessionId"
Write-Host "              or  .\setup-widget.ps1 -RevokeOld  (revokes every '$Label' session before re-pairing)"
