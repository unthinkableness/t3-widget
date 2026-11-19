// QR helper for setup-widget.ps1.
// Reads the setup link from STDIN (never argv, so it doesn't show in process lists),
// self-checks it by rendering a PNG in memory and decoding it with jsQR, then writes
// a self-contained HTML page with the QR as inline SVG to the path given in argv[2].
// It never prints the link or token. Output is one JSON line of non-secret facts.
//
// Usage: node qr.mjs <out.html> [--title "..."] [--info "line"]...
import { writeFileSync } from "node:fs";
import QRCode from "qrcode";
import jsQR from "jsqr";
import { PNG } from "pngjs";

const args = process.argv.slice(2);
const outPath = args[0];
const infoLines = [];
let title = "Pair T3 widget";
for (let i = 1; i < args.length; i++) {
  if (args[i] === "--title") title = args[++i];
  else if (args[i] === "--info") infoLines.push(args[++i]);
}
if (!outPath) {
  console.error("usage: node qr.mjs <out.html> [--title t] [--info line]...");
  process.exit(2);
}

const chunks = [];
for await (const c of process.stdin) chunks.push(c);
const link = Buffer.concat(chunks).toString("utf8").trim();

const FORMAT = /^t3widget:\/\/setup#v=1&url=[^&]+(&lan=[^&]+)?&token=[^&]+$/;
const formatOk = FORMAT.test(link);

// Self-check: render to PNG in memory, decode, compare. Nothing touches disk.
const pngBuf = await QRCode.toBuffer(link, { errorCorrectionLevel: "M", margin: 4, scale: 8 });
const png = PNG.sync.read(pngBuf);
const decoded = jsQR(new Uint8ClampedArray(png.data.buffer, png.data.byteOffset, png.data.length), png.width, png.height);
const decodeMatches = !!decoded && decoded.data === link;

let decodedFields = null;
if (decoded) {
  const frag = decoded.data.split("#")[1] ?? "";
  const p = new URLSearchParams(frag);
  decodedFields = {
    scheme: decoded.data.split("#")[0],
    v: p.get("v"),
    url: p.get("url"),
    lan: p.get("lan"),
    tokenPresent: !!p.get("token"),
    tokenLength: (p.get("token") ?? "").length,
    keys: [...p.keys()],
  };
}

const svg = await QRCode.toString(link, { type: "svg", errorCorrectionLevel: "M", margin: 4 });
const esc = (s) => s.replace(/[&<>"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[ch]);
const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>${esc(title)}</title>
<style>
  :root { color-scheme: light dark; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #f4f4f5; color: #18181b;
         font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
  @media (prefers-color-scheme: dark) { body { background: #18181b; color: #f4f4f5; } }
  main { max-width: 520px; padding: 24px 16px; text-align: center; }
  .qr { background: #fff; padding: 12px; border-radius: 12px; display: inline-block; }
  .qr svg { width: min(80vw, 420px); height: auto; display: block; }
  h1 { font-size: 20px; margin: 0 0 12px; }
  ul { text-align: left; padding-left: 20px; }
  .warn { font-size: 13px; opacity: .8; }
</style></head>
<body><main>
  <h1>${esc(title)}</h1>
  <div class="qr">${svg}</div>
  <p>Scan with your phone camera, then open the link in the T3 widget app.</p>
  ${infoLines.length ? `<ul>${infoLines.map((l) => `<li>${esc(l)}</li>`).join("")}</ul>` : ""}
  <p class="warn">This code contains a secret access token. Close this tab when done; the file is deleted automatically.</p>
</main></body></html>`;

writeFileSync(outPath, html, { encoding: "utf8", mode: 0o600 });

console.log(JSON.stringify({ ok: true, formatOk, decodeMatches, decoded: decodedFields }));
