# T3 Widget

An Android home-screen widget for T3 Code desktop. It shows, at a glance:

- how many threads are **running**, **need you** (approvals or questions), are **finished and waiting for review**, or are **done today**;
- the top thread that needs you (tap to open it in the T3 app);
- your provider **usage limits** (5-hour and weekly bars for Claude, Codex and Gemini).

It talks directly to the T3 Code server on your PC with a **read-only** token (`orchestration:read`). It can't send messages, approve anything or read your files.

> **Not affiliated with T3.** This is an independent hobby project. It isn't made, endorsed or supported by the T3 Code team, and it relies on undocumented internal APIs that may change in any T3 release.

## Repository layout

| Folder | What's in it |
|---|---|
| [`android/`](android/) | The widget app (Kotlin, Jetpack Glance). See [android/README.md](android/README.md) for build details. |
| [`pc-setup/`](pc-setup/) | `setup-widget.ps1`, which pairs the phone with your PC by QR code. |
| [`api/`](api/) | Notes on the T3 server API the widget uses, and a Node probe script. |
| `release.ps1` | Builds, verifies and publishes a GitHub release. |

## Install

1. On the phone, open the [latest release](https://github.com/unthinkableness/t3-widget/releases/latest) and download **`t3-widget.apk`**.
2. Open the file and allow your browser or file manager to install unknown apps when Android asks.
   - **Samsung phones:** if the install is blocked, turn off **Auto Blocker** first (Settings → Security and privacy → Auto Blocker). You can turn it back on after the install. Later updates go through the app's own updater.
3. Long-press the home screen → **Widgets** → **T3 Widget**, and place it (4×1, resizable to 4×2).

Requires Android 8.0 (API 26) or newer.

## Pair it with your PC

You need T3 Code running on a Windows PC and Node.js installed (for the QR code).

```powershell
cd pc-setup
.\setup-widget.ps1
```

The script mints a read-only token with the T3 CLI, finds your T3 Connect tunnel URL and your LAN URL, checks that both accept the token, and shows a one-time QR code. Scan it with the phone camera and the widget opens its settings with everything filled in. The token is never printed or saved to disk on the PC. See [pc-setup/README.md](pc-setup/README.md) for options (`-RevokeOld`, `-CopyLink`, `-Ttl`, ...).

You can also enter the server URL and token by hand in the widget's settings.

## Updates

Each GitHub release is tagged `v<versionName>` and has two assets:

- `t3-widget.apk`: the signed APK;
- `version.json`: `{"versionCode":N,"versionName":"x.y.z","sha256":"<sha256 of the apk>","notes":"...","minSdk":26}`.

The app checks `https://api.github.com/repos/unthinkableness/t3-widget/releases/latest`. When a newer `versionCode` is available it downloads the APK, checks its SHA-256 against `version.json`, and hands it to Android's package installer. You confirm the update on the phone.

Android only installs an update that's signed with **the same key** as the installed app, so every release is signed with one stable key, and `release.ps1` refuses to publish an APK whose certificate doesn't match [`signing-cert-sha256.txt`](signing-cert-sha256.txt).

## Building and releasing (maintainers)

Prerequisites on the PC: a JDK 21 at `%USERPROFILE%\.t3\tools\jdk21`, the Android SDK (build-tools 35.0.0) at `%LOCALAPPDATA%\Android\Sdk`, `git`, and the GitHub CLI (`gh auth login`).

**Signing key.** Release builds are signed with `%USERPROFILE%\.t3widget\signing\t3widget.keystore` (standard Android debug credentials by default). To use another location or password, copy [`android/keystore.properties.example`](android/keystore.properties.example) to `android/keystore.properties`, or set `T3WIDGET_KEYSTORE`, `T3WIDGET_KEYSTORE_PASSWORD`, `T3WIDGET_KEY_ALIAS` and `T3WIDGET_KEY_PASSWORD`. **Back this keystore up.** If it's lost, existing installs can't be updated and must be uninstalled first. The keystore is never committed (`*.keystore`, `*.jks` and `keystore.properties` are gitignored).

**Release:**

```powershell
.\release.ps1 -Version 0.4.0 -Notes "What changed"   # build, verify, commit, tag, push, publish
.\release.ps1 -Version 0.4.0 -DryRun                  # everything except commit/tag/push/release
```

`release.ps1` refuses a dirty tree or an existing tag, bumps `versionName` and `versionCode` (+1) in `android/app/build.gradle.kts`, runs the unit tests and `assembleRelease`, checks the APK's version and signing certificate, scans the tracked files for tokens and other private strings, writes `dist/t3-widget.apk` and `dist/version.json`, and then commits, tags `v<version>`, pushes and runs `gh release create`. A dry run restores `build.gradle.kts` afterwards. An optional list of extra private regexes can be kept outside the repo in `%USERPROFILE%\.t3widget\scrub-patterns.txt`.

## License

[MIT](LICENSE)
