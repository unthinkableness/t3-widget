# T3 widget – PC setup

`setup-widget.ps1` pairs the Android T3 widget app with this PC. It:

1. Mints a **read-only** T3 session (`orchestration:read`, `relay:read`) labelled `Android widget`.
2. Finds the T3 Connect tunnel URL (`/api/connect/link-state`) and this PC's LAN URL (`http://<wifi/ethernet IPv4>:<port>`, port from `~\.t3\userdata\server-runtime.json`). It then checks that both accept the token (`GET /api/auth/session` → `authenticated:true`).
3. Builds `t3widget://setup#v=1&url=<tunnel>&lan=<lan>&token=<token>`. If there is no tunnel, the LAN URL goes in `url` and `lan` is left out.
4. Shows that link as a QR code. The QR is generated offline into a temporary HTML page in `%TEMP%`, opened in your browser, and deleted when you press Enter or after `-CloseAfter` seconds. Before showing it, the script decodes the QR in memory and checks it matches the link.

The token is never printed, logged or written to a persistent file. If setup fails after the token is minted, the new session is revoked automatically.

## Usage

```powershell
cd <this folder>
.\setup-widget.ps1                 # QR code, 365-day token
.\setup-widget.ps1 -RevokeOld      # revoke earlier "Android widget" sessions first (no prompt)
.\setup-widget.ps1 -CopyLink       # copy the link to the clipboard instead of a QR
.\setup-widget.ps1 -Ttl 90d -Label "Pixel widget"
```

| Parameter | Default | Meaning |
|---|---|---|
| `-Ttl` | `365d` | Token lifetime (`30d`, `12h`, …) |
| `-Label` | `Android widget` | Session label; also used to find old sessions |
| `-RevokeOld` | off | Revoke existing sessions with this label without asking |
| `-KeepOld` | off | Keep them without asking (otherwise you are prompted) |
| `-CopyLink` | off | Clipboard instead of QR; the clipboard is cleared on Enter or after `-CloseAfter` |
| `-CloseAfter` | `180` | Seconds before the QR page is deleted or the clipboard is cleared |
| `-Verbose` | off | Also prints the decoded QR fields (scheme, url, lan, token length only) |

You need T3 Code running and Node.js installed. On first run the script runs `npm install` into `tools\` (`qrcode`, `jsqr`, `pngjs`); nothing is installed globally.

If the phone can't reach the LAN URL (the script tests it only from the PC), allow inbound TCP on the T3 port (3773) for **private** networks in Windows Firewall. The tunnel URL works from anywhere while T3 Connect shows the PC as reachable.

## Revoking

The summary prints the exact command, for example:

```powershell
& "$env:USERPROFILE\.t3\bin\t3.cmd" auth session list            # find the "Android widget" session id
& "$env:USERPROFILE\.t3\bin\t3.cmd" auth session revoke <session-id>
```

You can also run `.\setup-widget.ps1 -RevokeOld` to revoke every session with the label and pair again. After you revoke a session, the widget gets HTTP 401 and has to be paired again.

If you used `-CopyLink` and Windows clipboard history (Win+V) is on, also delete the entry from the history.
