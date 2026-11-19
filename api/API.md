# T3 Code widget data API

Researched against **T3 Code desktop, server v0.1.10, orchestration protocol 2** on 2026-11-19 by reading the server bundle (`resources/server.asar` → `apps/server/dist`) and testing against the live local server. `probe.mjs` in this folder runs the whole flow. It was tested and works with an `orchestration:read`-only token.

All token values in this document are redacted. Personal fields (`auth.email`, workspace ids) appear in real responses. The widget should drop them and never store them.

---

## 1. Transport overview

| Need | Endpoint | Kind | Scope |
|---|---|---|---|
| Thread list + statuses | `GET /api/orchestration/shell` | plain HTTP, JSON (gzip) | `orchestration:read` |
| Usage-limit bars (5h / weekly %) | `subscribeServerConfig` (first chunk) or `server.getConfig` on `GET /ws` | WebSocket, Effect-RPC JSON | `orchestration:read` |
| Token/cost history (not limits) | `server.getUsageSummary` on `/ws` | WebSocket RPC | `diagnostics:read` (not needed for the widget) |
| Check that a token is valid | `GET /api/auth/session` | HTTP | any |
| Server identity (no auth) | `GET /.well-known/t3/environment` | HTTP | none |

Other `/api/*` paths that don't exist return the SPA `index.html` with status 200, so a client should check `content-type: application/json`.

**Recommended credential:** one bearer token with the single scope `orchestration:read`. That one scope is enough for both calls. It can't send messages, approve requests, change settings or read files.

---

## 2. Connecting from the phone

### 2.1 URL options

The server listens on `0.0.0.0:3773` (`desktop-settings.json`: `serverExposureMode = "network-accessible"`). `server.getConfig` advertises `directEndpoints: [{kind:"lan", httpBaseUrl:"http://<LAN-IP>:3773/"}]`.

| Option | Base URL | Status | Notes |
|---|---|---|---|
| **LAN** | `http://<pc-lan-ip>:3773` | ✅ tested at the protocol level (server bound to all interfaces, bearer accepted with no origin restriction) | Works only on home Wi-Fi. Cleartext HTTP: the Android app needs a `network_security_config` cleartext exception for that host. The PC's IP can change (use a DHCP reservation or hostname). |
| **Tailscale** | `https://<pc>.<tailnet>.ts.net` | ⚠️ not tested here (not enabled on this PC) | `t3 pair --tailscale` publishes the server via Tailscale Serve HTTPS. The server honours `X-Forwarded-Proto`. This is the most robust off-LAN option and is already a supported T3 feature. The phone needs the Tailscale app connected. |
| **T3 Connect tunnel** | `https://<tunnelHostname>` | ❓ unknown | This is how the user's **T3 mobile app** reaches the PC today (session list shows `subject: cloud-connect`, `method: dpop-access-token`, `okhttp`, Android, public IPv6). The tunnel hostname is only readable with `relay:read` (`GET /api/connect/link-state` → `tunnelHostname`) or in the desktop app's Connect settings. The server code accepts a plain bearer from any origin (`EnvironmentAuth.authenticateRequest` has no tunnel check). I couldn't verify whether the Cloudflare edge or the relay lets a non-T3-Connect client through, and that would be an undocumented path. |
| **Relay `/v1/mobile/agent-activity`** | `https://<relay>.t3coderelay.com` | ❌ not usable | Cloud snapshot of Live-Activity state (`activeCount`, rows with `phase`). It needs a T3 Connect account DPoP token (`RelayDpopClientAuth`), it has no usage limits, and it only has data if "publish agent activity" is on. |

**Can the widget reuse the T3 mobile app's credential? No.** That session is a *DPoP-bound* access token (the proof key lives in the T3 app's keystore). It's short-lived (~15–20 min, refreshed through the T3 Connect cloud account), and it's private to that app. The widget needs its **own** token.

### 2.2 Getting a long-lived credential (two ways)

**A. CLI-issued session (simplest, custom TTL).** Run this on the PC:

```bat
t3 auth session issue --scope orchestration:read --ttl 365d --label "Android widget" --token-only
```

This prints a bearer token (~335 chars). Paste it into the widget's settings, or show it as a QR code. The default TTL is 30 days and the CLI accepts longer (`--ttl 365d`). The session shows up in `t3 auth session list` and is revoked with `t3 auth session revoke <sessionId>`.

**B. Pairing code + token exchange (QR onboarding in the app).** Run this on the PC:

```bat
t3 auth pairing create --scope orchestration:read --ttl 10m --label "Android widget" --base-url http://<pc-lan-ip>:3773 --json
```

The JSON has `{id, credential, scopes, expiresAt, pairUrl}`. `pairUrl` looks like `http://<host>:3773/pair#token=<credential>` (the credential is short, 12 chars here). `t3 pair --scope orchestration:read` prints the same thing as a terminal QR. The app scans or parses the URL, then exchanges the code once:

```http
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
&subject_token=<pairing credential>
&subject_token_type=urn:t3:params:oauth:token-type:environment-bootstrap
&requested_token_type=urn:ietf:params:oauth:token-type:access_token
&scope=orchestration:read
&client_label=Android%20widget
&client_device_type=mobile
&client_os=Android
```

Tested response:

```json
{"access_token":"<redacted, 304 chars>","issued_token_type":"urn:ietf:params:oauth:token-type:access_token",
 "token_type":"Bearer","expires_in":2591999,"scope":"orchestration:read"}
```

* Pairing codes are **one-time**: a second exchange gave `401 {"_tag":"EnvironmentAuthInvalidError","reason":"invalid_credential"}`.
* The exchanged token lasts **30 days** and comes with **no refresh token**. When it expires or gets revoked, every call returns 401 and the user re-pairs (or uses option A with a longer TTL).
* There's an optional DPoP variant (`dpop` header → `token_type: "DPoP"`). It's more secure but every request then needs a signed proof. Not needed for a read-only widget.

Store the token in `EncryptedSharedPreferences` / Android Keystore. Treat any 401 as "needs re-pair" and show that state on the widget.

### 2.3 Checking a token

```http
GET /api/auth/session
Authorization: Bearer <token>
```

```json
{"authenticated":true,"auth":{"policy":"remote-reachable","bootstrapMethods":["desktop-bootstrap","one-time-token"],
 "sessionMethods":["browser-session-cookie","bearer-access-token","dpop-access-token"],...},
 "scopes":["orchestration:read"],"permissions":["orchestration:read"],
 "sessionMethod":"bearer-access-token","expiresAt":"2026-12-19T13:40:02.716Z"}
```

A bad token still gets 200, with `{"authenticated":false,...}`. Use `expiresAt` to warn before expiry.

---

## 3. Threads: `GET /api/orchestration/shell`

```http
GET /api/orchestration/shell?omitIdleSubagents=1&omitOlderSettled=1
Authorization: Bearer <token>
x-t3-orchestration-protocol: 2
Accept-Encoding: gzip
```

* The `x-t3-orchestration-protocol: 2` header is required.
* `omitIdleSubagents=1` and `omitOlderSettled=1` (the value must be `"1"`) shrink the response from **2.7 MB / 989 threads to ~210 KB / 74 threads** (gzip on the wire, ~200 ms locally). Idle subagent children are left out. Live ones and ones waiting on the user stay in, which is all the widget needs.
* `resumeFrom=<snapshotSequence>` can return `{"resumable":true}` instead of a snapshot. Don't send it from a widget.
* Insufficient scope → `403 {"_tag":"EnvironmentScopeRequiredError","code":"insufficient_scope","requiredScope":"..."}`.

### 3.1 Response shape (redacted, trimmed)

```jsonc
{
  "schemaVersion": 1,
  "snapshotSequence": 726719,
  "threads": [ /* OrchestrationV2ThreadShell[] */ ],
  "archivedThreads": [],
  "projects": [ { "id": "<project-uuid>", "title": "No project", "workspaceRoot": "…", "projectIcon": {…}, … } ],
  "olderSettled": { "before": "2026-10-31T23:39:40.742Z", "asOfSequence": 726719, "counts": [ {"projectId":"…","count":4} ] },
  "projectIdentities": "…"
}
```

One thread (the fields the widget uses are marked ★):

```jsonc
{
  "id": "<thread-uuid>",                      // ★
  "projectId": "<project-uuid>",               // ★ → projects[].title
  "title": "Example thread",        // ★
  "creationSource": "web",                 // web | mobile | mcp | provider | server
  "createdBy": "user",
  "providerInstanceId": "claudeAgent",
  "modelSelection": { "instanceId": "claudeAgent", "model": "claude-sonnet-5-5" },
  "lineage": {                             // ★
    "parentThreadId": null, "relationshipToParent": null,   // "subagent" | "fork" | null
    "rootThreadId": "<thread-uuid>"           // (sideChat?: boolean)
  },
  "status": "completed",                   // ★ idle | preparing | starting | running | waiting | completed | failed | interrupted | cancelled | queued | rolled_back
  "activityRunStatus": null,               // ★ overrides status while a run is active
  "activeRunId": null,
  "latestRunStartedAt": "…", "latestRunCompletedAt": "2026-10-31T09:27:47.961Z", // ★ done-today
  "latestRunStopRequested": false,         // ★
  "latestRunBackgroundWake": false,        // ★
  "pendingRuntimeRequest": null,           // ★ or {"id","kind","createdAt","blocking","userOnly"?}
  "pendingBackgroundTasks": [],            // ★ [{taskId, kind: command|monitor|subagent|background_task, awaited?}]
  "hasActionableProposedPlan": false,
  "settledOverride": "settled", "settledAt": "…",   // ★ settled = user has dealt with it
  "snoozedUntil": null, "pinnedAt": null,
  "archivedAt": null, "deletedAt": null,   // ★
  "lastError": null, "usageLimitResetAt": null,
  "updatedAt": "…", "createdAt": "…", "lastVisitedAt": "…"
}
```

`pendingRuntimeRequest.kind` is `user_input` (a question or input) or an approval-type kind (for example a tool/command approval, secret or link card). `auth_refresh` is ignored.

### 3.2 Mapping to widget states

This mirrors the server's own logic in `packages/shared/src/agentAwareness.ts` (`resolveThreadAwarenessPhaseV2`, `projectThreadAwarenessV2`, `subagentAwaitsUser`). T3 uses the same logic for its mobile notifications and Live Activities, so the widget will agree with the app.

1. **Listed threads only:** `lineage.relationshipToParent !== "subagent" && lineage.sideChat !== true`, and `archivedAt == null && deletedAt == null`.
2. **Subagent roll-up:** a subagent child counts as "needs you" for its nearest listed ancestor when all of these hold: it has `pendingRuntimeRequest` with kind ≠ `auth_refresh`; the kind is not `user_input` unless `userOnly === true`; it isn't provider-native (`creationSource !== "provider"`); and `activityRunStatus != null`. Coordinator agents answer ordinary child questions, so those don't count.
3. **Phase per listed thread:**

```
if pendingRuntimeRequest.kind == "user_input"            → waiting_for_input     (NEEDS YOU)
elif pendingRuntimeRequest && kind != "auth_refresh"     → waiting_for_approval  (NEEDS YOU)
elif rolled-up subagent wait                             → input / approval      (NEEDS YOU)
else switch (activityRunStatus ?? status):
  preparing | starting                                   → starting              (RUNNING)
  running | waiting                                      → running               (RUNNING)
  completed:
     pendingBackgroundTasks has awaited or kind subagent/background_task,
     or (latestRunBackgroundWake && a non-PR-watch monitor task)
                                                         → running               (RUNNING; e.g. coordinator waiting on children)
     else                                                → completed
  failed                                                 → failed
  idle | queued | interrupted | cancelled | rolled_back  → (no state)
```

4. **Widget buckets** (as computed by `probe.mjs`):
   * `running`: phase starting/running.
   * `needsYou` (= `needsApproval + needsInput`): the waiting phases. **Top thread** = the most recent `pendingRuntimeRequest.createdAt`. Show `title` + project title.
   * `toReview`: phase completed, `settledOverride !== "settled"`, `latestRunStopRequested !== true`. This is the "finished, not yet looked at" set that T3 shows as a completion.
   * `failed`: phase failed and not settled.
   * `doneToday`: listed threads with `status == "completed"`, phase completed, and `latestRunCompletedAt` ≥ local midnight, whether settled or not.

Live result at test time: `running 5, needsYou 0, toReview 6, doneToday 6` out of 36 listed threads. The 5 running were 2 actively running threads plus 3 coordinators waiting on subagents.

**Deep link:** T3 uses the path `/threads/{environmentId}/{threadId}` internally (relay deep links). This research didn't establish the T3 Android app's URI scheme. The widget can at least open the web UI at `<baseUrl>/threads/<environmentId>/<threadId>` or simply launch the T3 app.

---

## 4. Usage limits: WebSocket RPC

Limit percentages **are exposed**, as `ServerProviderUsageLimits` on each provider in the server config. There's no HTTP route for them, so a single short WebSocket exchange is needed.

### 4.1 Connecting

```
GET ws://<host>:3773/ws?orchestrationProtocol=2          (wss:// behind Tailscale/TLS)
Authorization: Bearer <token>                            ← OkHttp can set this on the upgrade
```

* `orchestrationProtocol=2` in the **query string** is required. Without it you get `426 {"code":"orchestration_protocol_incompatible"}`.
* Auth falls back to the normal HTTP auth (bearer header) when no ticket is given. **Tested.**
* For clients that can't set headers: `POST /api/auth/websocket-ticket/rpc` with the bearer returns `{"ticket":"<redacted>","expiresAt":"…"}`, which is short-lived (≈1 min). Then connect with `&wsTicket=<ticket>`. **Tested.**

### 4.2 Wire format (Effect RPC, JSON serialization, one JSON object per text frame)

Client → server:
```json
{"_tag":"Request","id":"1","tag":"<method>","payload":{...},"headers":[]}
{"_tag":"Interrupt","requestId":"1"}          // stop a stream
{"_tag":"Ack","requestId":"1"}                // flow control for long-lived streams (not needed for one chunk)
{"_tag":"Pong"}                               // reply to {"_tag":"Ping"}
```
Server → client:
```json
{"_tag":"Exit","requestId":"1","exit":{"_tag":"Success","value":{...}}}     // unary result
{"_tag":"Exit","requestId":"1","exit":{"_tag":"Failure","cause":...}}       // error (e.g. EnvironmentAuthorizationError)
{"_tag":"Chunk","requestId":"1","values":[ ... ]}                            // stream items
```

### 4.3 Recommended call: `subscribeServerConfig`, take the first chunk, then close

```json
{"_tag":"Request","id":"1","tag":"subscribeServerConfig","headers":[],
 "payload":{"workspaceSnapshotsOnDemand":true,"providerCatalogsOnDemand":true,"providerCatalogDigests":true}}
```
→ first frame `{"_tag":"Chunk","requestId":"1","values":[{"version":1,"type":"snapshot","config":{...ServerConfig}}]}`. That's **~70 KB**. Send `Interrupt` and close with code 1000.

Alternative: the unary `{"tag":"server.getConfig","payload":{}}` → `Exit` with the same `ServerConfig`. It works, but it's **~507 KB** because it includes full model/skill/slash-command catalogs.

### 4.4 Shape: `config.providers[]` (redacted)

```jsonc
{
  "instanceId": "claudeAgent", "driver": "claudeAgent", "displayName": "…",
  "enabled": true, "status": "ready",
  "auth": { "status": "authenticated", "type": "Claude Max", "label": "Claude Max Subscription",
            "email": "<redacted>", "workspaceId": "<redacted>" },
  "usageLimits": {
    "checkedAt": "2026-11-19T13:39:51.354Z",
    "windows": [
      { "id": "five_hour",       "kind": "session", "label": "Session",        "usedPercent": 12, "resetsAt": "2026-11-19T18:09:59.689Z", "windowDurationMins": 300 },
      { "id": "seven_day",       "kind": "weekly",  "label": "Weekly",         "usedPercent": 59, "resetsAt": "2026-11-23T02:59:59.689Z", "windowDurationMins": 10080 },
      { "id": "seven_day_fable", "kind": "weekly",  "label": "Weekly · Fable", "usedPercent": 0,  "resetsAt": "2026-11-23T03:00:00.000Z", "windowDurationMins": 10080 }
    ],
    "resetCredits": { "availableCount": 1, "nextExpiresAt": "…", "nextCreditId": "…", "options": [ … ] }
  }
}
```

Schema (`ServerProviderUsageWindow`): `id`, `kind ∈ session|weekly|monthly|other`, `label`, `usedPercent` (0–100, may be fractional), optional `resetsAt`, `windowDurationMins`, `modelIds`, `providerIds`. `ServerProviderUsageLimits` can also carry `unavailable: {reason: "unsupported"|"probeFailed", message?}`, `zeroSpendCap`, `externalUsage {label,url}`. The schema comment says the server keeps the last good bars after a failed probe and clears them when an account is unavailable.

Observed providers on this PC:

| instanceId | Window ids (kind) | Widget bar |
|---|---|---|
| `claudeAgent` | `five_hour` (session), `seven_day` (weekly), `seven_day_fable` (weekly) | **Claude 5h** = `five_hour`, **Claude week** = `seven_day` |
| `codex` | `primary` (session, 300 min), `secondary` (weekly) | **Codex 5h** = `primary`, **Codex week** = `secondary` |
| `antigravity` | `gemini-5h`, `gemini-weekly`, `3p-5h`, `3p-weekly`, `gemini-argon-wtu-weekly` | **Gemini 5h/week** = `gemini-5h` / `gemini-weekly` |
| `opencode` | none (`unavailable.reason = "unsupported"`) | none |
| `*_history`, cursor, grok, … | `enabled:false` | skip |

Select by `kind` (first `session` window and first `weekly` window whose `id` doesn't start with `3p-`) rather than hard-coding ids, with the ids above as the preferred match. `checkedAt` was always within ~5 minutes of the request. The server refreshes limits in the background, so polling the config doesn't trigger extra provider calls.

---

## 5. Recommended widget approach (Android)

* **One-shot work, no held socket.** Use a `WorkManager` periodic job (Android's minimum is 15 min) plus refresh on tap and on `APPWIDGET_UPDATE`. Each run:
  1. `GET /api/orchestration/shell?omitIdleSubagents=1&omitOlderSettled=1` (OkHttp, gzip).
  2. Open `/ws?orchestrationProtocol=2` with `Authorization: Bearer`, send `subscribeServerConfig`, read the first `Chunk`, send `Interrupt`, close. Use a 10–15 s timeout.
  3. Run 1 and 2 in parallel, then compute buckets and update the `RemoteViews`. Total locally was ~300 ms.
* **Interval:** 15 min (the WorkManager floor) for background. Usage bars change slowly and `checkedAt` already lags a few minutes, so 15–30 min is fine for limits. Faster "needs you" alerts would need a held socket (`orchestration.subscribeShell` stream) or push, which is a bad fit for a widget. If near-real-time matters, keep using the T3 app's own notifications and let the widget show a summary.
* **Failure states:** network unreachable (off LAN without Tailscale) → show "offline" plus the last-updated time and keep cached values. 401 → "re-pair". 426 → "server protocol changed". 403 `insufficient_scope` → wrong token.
* **Data hygiene:** keep only counts, the titles needed for the top needs-you thread, and limit windows. Never store `auth.email`.
* **Version guard:** read `GET /.well-known/t3/environment` (no auth) → `serverVersion`, `orchestrationProtocolVersion` (expect `2`). These are undocumented internal APIs and may change between T3 releases.

---

## 6. What was minted during this research

* CLI session `widget-api-probe` (`orchestration:read` + `diagnostics:read`, 2 h TTL): **revoked** afterwards.
* Pairing code `widget-probe-pair` (`orchestration:read`, 5 min, one-time): consumed by the exchange test. The resulting 30-day session was **revoked** afterwards.
* No settings were changed and nothing was restarted.
