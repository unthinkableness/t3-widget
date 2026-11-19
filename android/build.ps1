# Builds the APKs with the portable JDK 21 and the user-level Android SDK.
#   .\build.ps1                       debug + minified release-style APK (signed with the debug key)
#   .\build.ps1 testDebugUnitTest     any Gradle task(s)
$env:JAVA_HOME = "$env:USERPROFILE\.t3\tools\jdk21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
[string[]]$tasks = if ($args.Count -gt 0) { $args } else { @("assembleDebug", "assembleRelease") }
& "$PSScriptRoot\gradlew.bat" @tasks
if ($LASTEXITCODE -eq 0) {
    foreach ($apk in @("debug\app-debug.apk", "release\app-release.apk")) {
        $p = Join-Path "$PSScriptRoot\app\build\outputs\apk" $apk
        if (Test-Path $p) { Write-Host ("APK: {0} ({1:N0} KB)" -f $p, ((Get-Item $p).Length / 1KB)) }
    }
}
exit $LASTEXITCODE
