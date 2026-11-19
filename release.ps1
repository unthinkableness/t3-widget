<#
.SYNOPSIS
  Builds, verifies and publishes a T3 widget release to GitHub.

.DESCRIPTION
  1. Refuses a dirty git tree, an existing tag, or a version that is not newer than the current one.
  2. Bumps versionName to -Version and versionCode by 1 in android/app/build.gradle.kts.
  3. Runs the unit tests and assembleRelease with the portable JDK (%USERPROFILE%\.t3\tools\jdk21)
     and the user-level Android SDK (%LOCALAPPDATA%\Android\Sdk).
  4. Checks the APK's version and that its signing certificate matches signing-cert-sha256.txt.
  5. Scans the tracked files for private strings (generic token patterns, plus the regexes in
     %USERPROFILE%\.t3widget\scrub-patterns.txt if that file exists; it is kept outside the repo).
  6. Writes dist\t3-widget.apk and dist\version.json (versionCode, versionName, sha256, notes, minSdk).
  7. Commits the bump, tags v<version>, pushes, and runs
     gh release create v<version> dist\t3-widget.apk dist\version.json.

  With -DryRun it does steps 1-6 and then restores build.gradle.kts, so nothing is committed,
  tagged, pushed or released.

.EXAMPLE
  .\release.ps1 -Version 0.4.0 -Notes "In-app updates."
.EXAMPLE
  .\release.ps1 -Version 0.4.0 -DryRun
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
  [string]$Notes = "",
  [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root       = $PSScriptRoot
$AndroidDir = Join-Path $Root "android"
$GradleFile = Join-Path $AndroidDir "app\build.gradle.kts"
$CertFile   = Join-Path $Root "signing-cert-sha256.txt"
$DistDir    = Join-Path $Root "dist"
$ApkOut     = Join-Path $AndroidDir "app\build\outputs\apk\release\app-release.apk"
$Tag        = "v$Version"
$JavaHome   = Join-Path $env:USERPROFILE ".t3\tools\jdk21"
$SdkHome    = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$BuildTools = Join-Path $SdkHome "build-tools\35.0.0"
$PrivatePatternsFile = Join-Path $env:USERPROFILE ".t3widget\scrub-patterns.txt"
$Utf8NoBom  = New-Object System.Text.UTF8Encoding($false)

function Write-Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "    [ok] $m" -ForegroundColor Green }

function Invoke-Native {
  # Runs a native command, streams its output, throws on a non-zero exit code.
  # (Windows PowerShell 5.1 turns native stderr into errors under EAP=Stop, so relax it here.)
  param([string]$Exe, [string[]]$CmdArgs, [switch]$Capture)
  $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
  try {
    if ($Capture) { $out = @(& $Exe @CmdArgs 2>&1 | ForEach-Object { "$_" }) }
    else { & $Exe @CmdArgs 2>&1 | ForEach-Object { Write-Host "    $_" } }
    $code = $LASTEXITCODE
  } finally { $ErrorActionPreference = $prev }
  if ($code -ne 0) {
    if ($Capture) { $out | ForEach-Object { Write-Host "    $_" } }
    throw "$(Split-Path $Exe -Leaf) $($CmdArgs -join ' ') failed (exit $code)."
  }
  if ($Capture) { return $out }
}

function Invoke-Git { Invoke-Native "git.exe" ([string[]]$args) -Capture }  # simple function: --flags pass through in $args

Push-Location $Root
$gradleOriginal = $null
$oldName = $null; $oldCode = $null
$committed = $false
try {
  # ------------------------------------------------------------------ preflight
  Write-Step "Preflight"
  foreach ($p in @($JavaHome, $SdkHome, $BuildTools, $GradleFile, $CertFile)) {
    if (-not (Test-Path $p)) { throw "Missing: $p" }
  }
  $dirty = Invoke-Git status --porcelain
  if (@($dirty | Where-Object { $_ }).Count -gt 0) {
    throw "Git tree is dirty. Commit or stash first:`n$($dirty -join "`n")"
  }
  Write-Ok "git tree clean"
  $existing = Invoke-Git tag --list $Tag
  if (@($existing | Where-Object { $_ }).Count -gt 0) { throw "Tag $Tag already exists." }
  if (-not $DryRun) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "GitHub CLI (gh) not found." }
    Invoke-Native "gh" @("auth", "status") -Capture | Out-Null
    Invoke-Git fetch --quiet origin | Out-Null
    $behind = [int]((Invoke-Git rev-list --count "HEAD..@{u}") | Select-Object -First 1)
    if ($behind -gt 0) { throw "Local branch is $behind commit(s) behind its upstream. Pull first." }
    Write-Ok "gh authenticated, branch up to date with upstream"
  }

  $expectedCert = ((Get-Content $CertFile -Raw).Trim() -replace '[:\s]', '').ToLowerInvariant()
  if ($expectedCert -notmatch '^[0-9a-f]{64}$') { throw "$CertFile does not hold a SHA-256 hex digest." }

  # ------------------------------------------------------------------ bump version
  $gradleOriginal = [IO.File]::ReadAllText($GradleFile)
  $codeMatch = [regex]::Match($gradleOriginal, '(?m)^(\s*versionCode\s*=\s*)(\d+)')
  $nameMatch = [regex]::Match($gradleOriginal, '(?m)^(\s*versionName\s*=\s*)"([^"]*)"')
  if (-not $codeMatch.Success -or -not $nameMatch.Success) { throw "versionCode/versionName not found in $GradleFile." }
  $oldCode = [int]$codeMatch.Groups[2].Value
  $oldName = $nameMatch.Groups[2].Value
  $newCode = $oldCode + 1
  if ([version]$Version -le [version]$oldName) { throw "Version $Version is not newer than the current $oldName." }
  $bumped = $gradleOriginal.Remove($codeMatch.Groups[2].Index, $codeMatch.Groups[2].Length).Insert($codeMatch.Groups[2].Index, "$newCode")
  $nameMatch = [regex]::Match($bumped, '(?m)^(\s*versionName\s*=\s*)"([^"]*)"')
  $bumped = $bumped.Remove($nameMatch.Groups[2].Index, $nameMatch.Groups[2].Length).Insert($nameMatch.Groups[2].Index, $Version)
  [IO.File]::WriteAllText($GradleFile, $bumped, $Utf8NoBom)
  Write-Step "Version $oldName ($oldCode) -> $Version ($newCode)"

  # ------------------------------------------------------------------ build
  Write-Step "Unit tests + assembleRelease"
  $env:JAVA_HOME = $JavaHome
  $env:ANDROID_HOME = $SdkHome
  $env:ANDROID_SDK_ROOT = $SdkHome
  if (Test-Path $ApkOut) { Remove-Item $ApkOut -Force }
  Push-Location $AndroidDir
  try { Invoke-Native (Join-Path $AndroidDir "gradlew.bat") @("--no-daemon", "testDebugUnitTest", "assembleRelease") }
  finally { Pop-Location }
  if (-not (Test-Path $ApkOut)) { throw "Release APK not found at $ApkOut." }
  Write-Ok "built $ApkOut"

  # ------------------------------------------------------------------ verify APK
  Write-Step "Verifying APK"
  $env:PATH = "$JavaHome\bin;$env:PATH"
  $certOut = Invoke-Native (Join-Path $BuildTools "apksigner.bat") @("verify", "--print-certs", $ApkOut) -Capture
  $digests = @($certOut | ForEach-Object { if ($_ -match 'Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)') { $Matches[1].ToLowerInvariant() } })
  if ($digests.Count -ne 1) { throw "Expected exactly one signer, found $($digests.Count)." }
  if ($digests[0] -ne $expectedCert) {
    throw "Signing certificate mismatch!`n  APK:      $($digests[0])`n  expected: $expectedCert`nPhones with the app installed would refuse this update. Check the keystore (see README)."
  }
  Write-Ok "signing cert SHA-256 $($digests[0]) matches signing-cert-sha256.txt"

  $badging = Invoke-Native (Join-Path $BuildTools "aapt2.exe") @("dump", "badging", $ApkOut) -Capture
  $pkgLine = $badging | Where-Object { $_ -like "package:*" } | Select-Object -First 1
  if ($pkgLine -notmatch "versionCode='(\d+)'" -or [int]$Matches[1] -ne $newCode) { throw "APK versionCode is not $newCode ($pkgLine)." }
  if ($pkgLine -notmatch "versionName='([^']+)'" -or $Matches[1] -ne $Version) { throw "APK versionName is not $Version ($pkgLine)." }
  $sdkLine = $badging | Where-Object { $_ -match "^(min)?[sS]dkVersion:'(\d+)'" } | Select-Object -First 1
  if (-not $sdkLine -or $sdkLine -notmatch "'(\d+)'") { throw "Could not read minSdk from the APK." }
  $minSdk = [int]$Matches[1]
  Write-Ok "APK reports versionCode $newCode, versionName $Version, minSdk $minSdk"

  # ------------------------------------------------------------------ privacy scan
  Write-Step "Scanning tracked files for private strings"
  $patterns = @('eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}', '-----BEGIN [A-Z ]*PRIVATE KEY-----', 'gh[pousr]_[A-Za-z0-9]{30,}')
  if (Test-Path $PrivatePatternsFile) {
    $patterns += @(Get-Content $PrivatePatternsFile | Where-Object { $_.Trim() -and -not $_.TrimStart().StartsWith("#") } | ForEach-Object { $_.Trim() })
    Write-Ok "using $($patterns.Count) patterns (incl. $PrivatePatternsFile)"
  } else {
    Write-Host "    (no $PrivatePatternsFile; generic patterns only)" -ForegroundColor Yellow
  }
  $hits = @()
  foreach ($f in (Invoke-Git ls-files)) {
    $path = Join-Path $Root $f
    if (-not (Test-Path $path -PathType Leaf) -or $f -match '\.(jar|png|jpg|webp|ico)$') { continue }
    $text = [IO.File]::ReadAllText($path)
    foreach ($p in $patterns) { if ([regex]::IsMatch($text, $p)) { $hits += "$f  (pattern #$([array]::IndexOf($patterns, $p)))" } }
  }
  if ($hits.Count -gt 0) { throw "Private strings found in tracked files:`n  $($hits -join "`n  ")" }
  Write-Ok "no private strings in $(@(Invoke-Git ls-files).Count) tracked files"

  # ------------------------------------------------------------------ dist
  Write-Step "Writing dist\"
  New-Item -ItemType Directory -Force $DistDir | Out-Null
  $apkDist = Join-Path $DistDir "t3-widget.apk"
  $jsonDist = Join-Path $DistDir "version.json"
  Copy-Item $ApkOut $apkDist -Force
  $sha = (Get-FileHash $apkDist -Algorithm SHA256).Hash.ToLowerInvariant()
  if (-not $Notes) { $Notes = "T3 widget $Version" }
  $meta = [ordered]@{ versionCode = $newCode; versionName = $Version; sha256 = $sha; notes = $Notes; minSdk = $minSdk }
  [IO.File]::WriteAllText($jsonDist, ($meta | ConvertTo-Json -Compress), $Utf8NoBom)
  Write-Ok ("t3-widget.apk ({0:N0} KB, sha256 {1})" -f ((Get-Item $apkDist).Length / 1KB), $sha)
  Write-Ok "version.json $([IO.File]::ReadAllText($jsonDist))"

  if ($DryRun) {
    Write-Host ""
    Write-Host "DRY RUN: built and verified $Tag. Not committing, tagging, pushing or releasing." -ForegroundColor Yellow
    return
  }

  # ------------------------------------------------------------------ publish
  Write-Step "Commit, tag, push"
  Invoke-Git add "android/app/build.gradle.kts" | Out-Null
  Invoke-Git commit -m "Release $Tag" | Out-Null
  $committed = $true
  Invoke-Git tag -a $Tag -m "T3 widget $Version" | Out-Null
  Invoke-Git push origin HEAD | Out-Null
  Invoke-Git push origin $Tag | Out-Null
  Write-Ok "pushed commit and tag $Tag"

  Write-Step "Creating GitHub release $Tag"
  $notesFile = Join-Path ([IO.Path]::GetTempPath()) ("t3widget-notes-" + [guid]::NewGuid().ToString("N") + ".md")
  [IO.File]::WriteAllText($notesFile, $Notes, $Utf8NoBom)
  try {
    Invoke-Native "gh" @("release", "create", $Tag, $apkDist, $jsonDist, "--title", $Tag, "--notes-file", $notesFile, "--latest")
  } finally { Remove-Item $notesFile -Force -ErrorAction SilentlyContinue }
  $url = (Invoke-Native "gh" @("release", "view", $Tag, "--json", "url", "--jq", ".url") -Capture) | Select-Object -First 1
  Write-Host ""
  Write-Host "Released $Tag : $url" -ForegroundColor Green
} finally {
  # Undo the version bump unless it was committed (dry run or failure before the commit).
  if ($gradleOriginal -ne $null -and -not $committed) {
    [IO.File]::WriteAllText($GradleFile, $gradleOriginal, $Utf8NoBom)
    Write-Host "    build.gradle.kts restored to $oldName ($oldCode)" -ForegroundColor DarkGray
  }
  Pop-Location
}
