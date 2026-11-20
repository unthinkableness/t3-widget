# T3 Widget (Android)

Compact 4x1 home-screen widget for T3 Code (resizable to 4x2). Kotlin + Jetpack Glance, a "Live updates"
foreground service holding one WebSocket, WorkManager as a 15-minute fallback, Preferences DataStore with a
Keystore-encrypted token. Target: Galaxy Z Flip7, Android 16 / One UI (minSdk 26, targetSdk 35).

## Build

```powershell
.\build.ps1                      # debug + minified release-style APK; uses ~/.t3/tools/jdk21 and the user SDK
.\build.ps1 testDebugUnitTest    # unit tests (any Gradle task works)
```

| APK | Size | Notes |
|---|---|---|
| `app\build\outputs\apk\release\app-release.apk` | ~2.0 MB | R8-minified, resources shrunk, **signed with the local debug key**. Use this one. |
| `app\build\outputs\apk\debug\app-debug.apk` | ~11.6 MB | Unminified, debuggable. |

Both share the applicationId and signing key, so either installs over the other (and over v0.1).

Optional live check against a real server (skipped unless `T3_TOKEN_FILE` is set; the token is never printed):

```powershell
$env:T3_TOKEN_FILE = "C:\path\to\token.txt"; $env:T3_BASE_URL = "https://<your-tunnel>.t3coderelay.com"
.\build.ps1 testDebugUnitTest --tests dev.local.t3widget.LiveServerTest
```

## Install

- adb: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk`
- Or copy the APK to the phone, open it in My Files, allow "Install unknown apps" for My Files.

Then long-press the home screen > Widgets > "T3 Widget" > "T3 Code status".

## Setup link / QR code

The PC-side script shows a QR code for this link. Scanning it with the camera (or tapping the link, or
**Settings > Paste setup link**) opens the widget's settings, asks for confirmation, then saves the settings,
turns demo data off, refreshes and starts live updates.

```
t3widget://setup#v=1&url=<tunnel base url>&lan=<optional lan base url>&token=<token>
```

- Parameters are in the **fragment** (`#`), `&`-separated, each value **percent-encoded**
  (JavaScript `encodeURIComponent`, Python `urllib.parse.quote(value, safe="")`). A literal `+` is kept as `+`.
- `v` (required): `1`. Links with a higher version are rejected with "update the app".
- `url` (required): primary base URL, normally the T3 Connect tunnel, e.g. `https://<your-tunnel>.t3coderelay.com`.
  http(s) only; a trailing `/` is dropped.
- `lan` (optional, may be empty or absent): home-network base URL, e.g. `http://<pc-lan-ip>:3773`.
- `token` (required): bearer token. Scopes: `orchestration:read` (required) and `relay:read` (optional, lets the
  widget follow tunnel hostname changes). Example: `t3 auth session issue --scope orchestration:read --scope relay:read --ttl 365d --label "Android widget" --token-only`.
- Parameter order doesn't matter. The query form (`t3widget://setup?v=1&…`) is accepted as a fallback.

Example (default values for this PC; `<token>` is a placeholder):

```
t3widget://setup#v=1&url=https%3A%2F%2F<your-tunnel>.t3coderelay.com&lan=http%3A%2F%2F<pc-lan-ip>%3A3773&token=<token>
```

The URL fields and token can still be edited by hand in Settings.

## Connectivity

- **Endpoint order.** On Wi-Fi/Ethernet with a LAN URL: LAN first (1.5 s probe), then the tunnel. On mobile data:
  tunnel first (6 s probe), then LAN (covers a VPN). The endpoint that last worked on that kind of network is tried
  first. Each candidate is probed with the unauthenticated `GET /.well-known/t3/environment`, which also gives the
  cached `environmentId`. The token is only sent to a LAN address whose `environmentId` matches the one already
  seen, so it isn't sent to a stranger's server on another Wi-Fi network. If a request fails mid-way, it fails over
  to the other endpoint once.
- **Tunnel hostname.** After a successful sync, if the token has `relay:read`, the widget reads
  `GET /api/connect/link-state` (at most every 10 min; after a 403 only daily) and saves a changed `tunnelHostname`.
  It only replaces a URL that already looks like a T3 tunnel (same parent domain) or an empty one, never a
  hand-entered Tailscale URL.
- **Retries.** One-shot refreshes retry transient failures after 2 s and 6 s. The live service reconnects with
  backoff 2 s → 5 s → 10 s → 20 s → 40 s → 60 s → 2 min → 5 min (±20 %), and wakes early when the network comes
  back, on unlock, on a manual refresh or when settings change. Without the service, an "Offline" failure enqueues a
  WorkManager job with a CONNECTED constraint.
- **Token storage.** AES-256-GCM key in the Android Keystore; DataStore only holds the ciphertext. A v0.1
  plain-text token is migrated on first read. Backup is off (a restored ciphertext couldn't be decrypted anyway).

| Failure | Widget shows | Style |
|---|---|---|
| Phone has no network | `Offline · 12m` | muted, last values kept |
| Tunnel and LAN don't answer (PC off, 502/530) | `PC unreachable · 12m` | muted, last values kept |
| 401 | `⚠ Re-pair needed · 12m` | red |
| 403 | `⚠ Token lacks access` | red |
| 426 / protocol mismatch | `⚠ T3 updated; update widget` | red |
| No success for 40 min | `Stale · 45m · tap to refresh` | grey dot |

## Live updates

A foreground service ("Live updates", on by default, toggle in Settings) holds one authenticated WebSocket to
`/ws?orchestrationProtocol=2`:

1. `GET /api/orchestration/shell?omitIdleSubagents=1&omitOlderSettled=1` (~20 KB gzip) → in-memory model + `snapshotSequence`.
2. `orchestration.subscribeShell` with `{afterSequence: <snapshotSequence>, httpSnapshotFallback: true, omitIdleSubagents: true, omitOlderSettled: true}`.
   The server streams only deltas (`thread.updated`, `thread.removed`, `project.updated/removed`, plus small
   project-metadata `snapshot` items). If it can't resume from the cursor it sends `snapshot-required` and the
   widget re-fetches the HTTP snapshot and resubscribes. Every `Chunk` is answered with `{"_tag":"Ack","requestId":…}`
   (Effect RPC flow control: without it the server stops after the first chunk).
3. Deltas are applied to the model; the widget is recomputed at most every 1.5 s and only redrawn when something
   changed (or as a heartbeat).
4. Usage limits: a short `subscribeServerConfig` request on the same socket every 5 min (15 min while the screen is
   off), first chunk then `Interrupt`. This also refreshes "updated" so a quiet-but-live widget never looks stale.
5. Keep-alive: app-level `{"_tag":"Ping"}` every 25 s (server answers `Pong`), OkHttp WebSocket pings every 30 s,
   80 s of silence = dead socket → reconnect. Full HTTP resync every 30 min as a safety net.

Verified against the live server (v0.1.10) through the tunnel and on the LAN: the stream emits `thread.updated`
deltas as threads change, the server answers app-level Ping, and the delta-applied model matched a fresh HTTP
snapshot. Not yet tested on a phone.

Service details: type `specialUse` (not `dataSync`, which has a daily limit on Android 15+), notification channel
"T3 widget live updates" at minimum importance, silent, showing e.g. "2 running · 1 needs you"; tapping it opens T3.
It listens for network changes (`registerDefaultNetworkCallback`), `USER_PRESENT` (refresh on unlock) and screen
on/off while it runs. It starts on boot / app update, when a widget is added, after setup and on a widget refresh
tap. It stops when the last widget is removed, when the toggle is off or demo data is on. The 15-minute WorkManager
job stays as a fallback and restarts the service if it died (allowed from the background once the app is exempt
from battery optimisation).

**Samsung:** One UI kills background apps aggressively. In Settings > "Keep it running", allow the notification and
set battery to Unrestricted (the app asks via the system dialog; "App info" opens the page with the Battery option).

## Taps

| Where | Action |
|---|---|
| Card background | Launch T3 (`com.t3tools.t3code`, else `com.t3tools.t3code.preview`), else the server URL in a browser, else Settings |
| "needs you" chip (both sizes), needs-you title row (4x2) | Open the top needs-you thread: `ACTION_VIEW https://app.t3.codes/threads/{environmentId}/{threadId}` (segments URL-encoded) targeted at the installed T3 package; without T3 installed, the plain URL. With nothing waiting, launch T3. |
| Usage bars, `↻`/`●` time in 4x2 | Refresh now (also revives the live service) |

The 4x2 header shows `● 2m` while the live socket is connected, `↻ 2m` otherwise.

## Layout

| Path | What |
|---|---|
| `data/Models.kt` | `WidgetState` (+ top thread id, environment id, error kind, live), `Settings`, `ErrorKind` |
| `data/ThreadSummary.kt` | Pure shell → running / needs-you / top thread mapping (mirrors T3's agentAwareness) and usage bars |
| `data/ShellModel.kt` | In-memory shell kept current by `subscribeShell` deltas |
| `data/SetupLink.kt` | `t3widget://setup` parser/builder |
| `data/T3Api.kt` | HTTP + one-shot WebSocket calls, error categorisation |
| `data/Endpoints.kt` | Tunnel/LAN ordering, probing, remembered endpoint |
| `data/RemoteT3Repository.kt` | One-shot fetch with failover, tunnel-hostname refresh |
| `data/FakeT3Repository.kt` | Demo data (drifts each minute) |
| `data/Store.kt`, `data/TokenVault.kt` | DataStore + Keystore-encrypted token |
| `live/LiveSession.kt` | The long-lived socket, deltas, debounce, usage polling, keep-alive |
| `live/LiveUpdateService.kt` | Foreground service, notification, network/unlock signals, boot receiver |
| `work/Refresher.kt` | One-shot refresh with retry, WorkManager scheduling/watchdog |
| `widget/T3Widget.kt` | Glance UI (4x1 compact, 4x2 tall), receiver, tap actions |
| `T3Links.kt` | Intents for the T3 app and thread links |
| `ui/SettingsActivity.kt` | Settings, setup-link handler, permission prompts, status |

## In-app updates

The app updates itself from this repo's GitHub releases (`UpdateCore.REPO`).

- It checks `releases/latest` every 6 hours, when the app opens (at most every 10 minutes) and from **Check for updates** in settings. It sends an ETag, so an unchanged release costs one cheap request.
- A release needs the `t3-widget.apk` and `version.json` assets (`versionCode`, `versionName`, `sha256`, `notes`, `minSdk`). `release.ps1` at the repo root builds and publishes both.
- The APK is installed only if its SHA-256, package name, versionCode and signing cert all match. Automatic updates can install without a prompt when Android permits it. Tapping **Install** requests the Android confirmation screen.
- **Auto-install updates** (on by default) installs as soon as an update is downloaded. When it's off, you get an "Update available" notification instead. A blue dot on the T3 tile means an update is waiting for you to confirm the install.
- On Samsung phones, Auto Blocker must be off, and T3 Widget needs "Install unknown apps" permission.
