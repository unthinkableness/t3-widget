#!/usr/bin/env node
// probe.mjs — minimal T3 Code widget data probe (server v0.1.10, orchestration protocol 2).
//
// Prints thread status counts (running / needs you / to review / done today), the top
// thread that needs you, and provider usage-limit bars as one JSON object.
//
// Usage:
//   T3_TOKEN=<bearer> [T3_BASE_URL=http://127.0.0.1:3773] node probe.mjs
//
// T3_TOKEN needs only the `orchestration:read` scope. Get one with either
//   t3 auth session issue --scope orchestration:read --ttl 365d --label "Android widget" --token-only
// or by exchanging a pairing code (see API.md §2). The token is never printed.
//
// Requires Node 22+ (global fetch and WebSocket with a `headers` option).

const BASE = (process.env.T3_BASE_URL ?? "http://127.0.0.1:3773").replace(/\/+$/, "");
const TOKEN = process.env.T3_TOKEN?.trim();
if (!TOKEN) {
  console.error("Set T3_TOKEN to a bearer token with the orchestration:read scope.");
  process.exit(2);
}
const AUTH = { authorization: `Bearer ${TOKEN}` };

// ---------------------------------------------------------------- threads (HTTP)

async function fetchShell() {
  const res = await fetch(`${BASE}/api/orchestration/shell?omitIdleSubagents=1&omitOlderSettled=1`, {
    headers: { ...AUTH, "x-t3-orchestration-protocol": "2" },
  });
  if (!res.ok) throw new Error(`shell snapshot HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
  return res.json();
}

// The helpers below mirror packages/shared/src/agentAwareness.ts in the server bundle
// (resolveThreadAwarenessPhaseV2 / projectThreadAwarenessV2 / subagentAwaitsUser), which is
// what T3 uses for its own mobile notifications and Live Activities.
const isListed = (t) => t.lineage?.relationshipToParent !== "subagent" && t.lineage?.sideChat !== true;
const isProviderNativeSubagent = (t) =>
  t.lineage?.relationshipToParent === "subagent" && t.creationSource === "provider";
const holdsCompletion = (tasks = []) =>
  tasks.some((x) => x.awaited === true || x.kind === "subagent" || x.kind === "background_task");
const monitorHoldsWake = (t) =>
  t.latestRunBackgroundWake === true &&
  (t.pendingBackgroundTasks ?? []).some((x) => x.kind === "monitor" && !String(x.taskId ?? "").startsWith("pull-request-watch:"));

function subagentAwaitsUser(t) {
  const kind = t.pendingRuntimeRequest?.kind;
  return (
    kind !== undefined && kind !== "auth_refresh" &&
    (kind !== "user_input" || t.pendingRuntimeRequest?.userOnly === true) &&
    t.lineage?.relationshipToParent === "subagent" && !isProviderNativeSubagent(t) &&
    t.activityRunStatus != null
  );
}

function phaseOf(t, subagentWait) {
  const req = t.pendingRuntimeRequest;
  if (req?.kind === "user_input") return "waiting_for_input";
  if (req && req.kind !== "auth_refresh") return "waiting_for_approval";
  if (subagentWait) return subagentWait.kind === "user_input" ? "waiting_for_input" : "waiting_for_approval";
  switch (t.activityRunStatus ?? t.status) {
    case "preparing": case "starting": return "starting";
    case "running": case "waiting": return "running";
    case "completed": return holdsCompletion(t.pendingBackgroundTasks) || monitorHoldsWake(t) ? "running" : "completed";
    case "failed": return "failed";
    default: return null; // idle, queued, interrupted, cancelled, rolled_back
  }
}

function summarizeThreads(shell) {
  const live = shell.threads.filter((t) => t.archivedAt == null && t.deletedAt == null);
  const byId = new Map(live.map((t) => [t.id, t]));
  const projects = new Map((shell.projects ?? []).map((p) => [p.id, p]));

  // Roll a subagent's user-only wait up to its nearest listed ancestor (as the server does).
  const listedAncestor = (t) => {
    let cur = t;
    for (let i = 0; i < 50 && cur && !isListed(cur); i++) cur = byId.get(cur.lineage?.parentThreadId);
    return cur && isListed(cur) ? cur : byId.get(t.lineage?.rootThreadId);
  };
  const subagentWaits = new Map();
  for (const t of live) {
    if (!subagentAwaitsUser(t)) continue;
    const top = listedAncestor(t);
    if (top && !subagentWaits.has(top.id)) subagentWaits.set(top.id, { kind: t.pendingRuntimeRequest.kind, createdAt: t.pendingRuntimeRequest.createdAt });
  }

  const startOfToday = new Date(); startOfToday.setHours(0, 0, 0, 0);
  const counts = { running: 0, needsYou: 0, needsApproval: 0, needsInput: 0, toReview: 0, failed: 0, doneToday: 0 };
  const needing = [];

  for (const t of live.filter(isListed)) {
    const sub = subagentWaits.get(t.id) ?? null;
    const phase = phaseOf(t, sub);
    const settled = t.settledOverride === "settled";
    const completedToday = t.latestRunCompletedAt != null && new Date(t.latestRunCompletedAt) >= startOfToday;

    if (phase === "waiting_for_input" || phase === "waiting_for_approval") {
      counts.needsYou++;
      phase === "waiting_for_input" ? counts.needsInput++ : counts.needsApproval++;
      needing.push({
        threadId: t.id,
        title: t.title,
        project: projects.get(t.projectId)?.title ?? null,
        phase,
        since: t.pendingRuntimeRequest?.createdAt ?? sub?.createdAt ?? t.updatedAt,
        viaSubagent: !t.pendingRuntimeRequest && !!sub,
      });
    } else if (phase === "starting" || phase === "running") {
      counts.running++;
    } else if (phase === "completed" && !settled && t.latestRunStopRequested !== true) {
      counts.toReview++; // finished, not yet settled — the "unread" set
    } else if (phase === "failed" && !settled) {
      counts.failed++;
    }
    if (t.status === "completed" && completedToday && phase === "completed") counts.doneToday++;
  }
  needing.sort((a, b) => String(b.since).localeCompare(String(a.since)));
  return { counts, topNeedsYou: needing[0] ?? null, needsYou: needing.slice(0, 5), listedThreads: live.filter(isListed).length };
}

// ---------------------------------------------------------------- usage limits (WebSocket RPC)

function fetchProviderLimits() {
  return new Promise((resolve, reject) => {
    const wsUrl = `${BASE.replace(/^http/, "ws")}/ws?orchestrationProtocol=2`;
    // Node's (undici) WebSocket accepts { headers }; OkHttp on Android can set the header too.
    // Alternative for clients that cannot: POST /api/auth/websocket-ticket/rpc and add &wsTicket=...
    const ws = new WebSocket(wsUrl, { headers: AUTH });
    const timer = setTimeout(() => { ws.close(); reject(new Error("WebSocket timeout")); }, 15000);
    ws.onopen = () => ws.send(JSON.stringify({
      _tag: "Request", id: "1", tag: "subscribeServerConfig", headers: [],
      payload: { workspaceSnapshotsOnDemand: true, providerCatalogsOnDemand: true, providerCatalogDigests: true },
    }));
    ws.onmessage = (ev) => {
      const msg = JSON.parse(String(ev.data));
      if (msg._tag === "Ping") { ws.send(JSON.stringify({ _tag: "Pong" })); return; }
      if (msg.requestId !== "1") return;
      clearTimeout(timer);
      if (msg._tag === "Chunk") {
        const snap = msg.values.find((v) => v.type === "snapshot");
        try { ws.send(JSON.stringify({ _tag: "Interrupt", requestId: "1" })); } catch {}
        ws.close(1000);
        return snap ? resolve(snap.config) : reject(new Error("first chunk had no snapshot"));
      }
      ws.close();
      reject(new Error(`RPC ${msg._tag}: ${JSON.stringify(msg.exit ?? msg).slice(0, 300)}`));
    };
    ws.onerror = (e) => { clearTimeout(timer); reject(new Error(`WebSocket error: ${e.message ?? e.type}`)); };
  });
}

const PROVIDER_NAMES = { claudeAgent: "Claude", codex: "Codex", antigravity: "Gemini" };

function summarizeLimits(config) {
  return config.providers
    .filter((p) => p.enabled && p.usageLimits && (p.usageLimits.windows?.length ?? 0) > 0)
    .map((p) => ({
      instanceId: p.instanceId,
      driver: p.driver,
      name: PROVIDER_NAMES[p.instanceId] ?? p.displayName ?? p.instanceId,
      plan: p.auth?.label ?? null, // e.g. "Claude Max Subscription" (auth.email deliberately dropped)
      checkedAt: p.usageLimits.checkedAt,
      windows: p.usageLimits.windows.map((w) => ({
        id: w.id, kind: w.kind, label: w.label,
        usedPercent: Math.round(w.usedPercent * 10) / 10,
        resetsAt: w.resetsAt ?? null,
        windowDurationMins: w.windowDurationMins ?? null,
      })),
      resetCreditsAvailable: p.usageLimits.resetCredits?.availableCount ?? null,
    }));
}

// ---------------------------------------------------------------- main

const t0 = Date.now();
const [shell, config] = await Promise.all([fetchShell(), fetchProviderLimits()]);
const threads = summarizeThreads(shell);
console.log(JSON.stringify({
  fetchedAt: new Date().toISOString(),
  elapsedMs: Date.now() - t0,
  environment: { id: config.environment?.environmentId, label: config.environment?.label, serverVersion: config.environment?.serverVersion },
  ...threads,
  limits: summarizeLimits(config),
}, null, 2));
