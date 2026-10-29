// Evidence Collector · v2 取证台（仪器校准 + 相位模型验证）
// 在真实资产落地前先自证三件事，否则报告里的「0 违规」没有意义：
//   ① 混合线残差能抓到违规（正对照：错红 #E03127 / 纯灰描边）且不冤枉合法 AA（负对照：白↔红交界）
//   ② 竖干「纯覆盖列」的解析模型（p ≥ 2−w）与 Chrome 真实 AA 是否对得上
//   ③ 07 的 3.6300em(opsz32) / 3.8473em(opsz14) / LH 1.2886em 能否在浏览器复现
// 用法：node design/.ev-v2-bench.mjs
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG, toRGBA } from './.ev-png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const EVI = join(ROOT, 'design/brand/shots/evidence');
const DER = join(EVI, '_v2');
mkdirSync(DER, { recursive: true });
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9561;
const FONT = 'file://' + join(ROOT, 'assets/fonts/InterVariable.woff2');
const B = 48;

const C = { card: [0x23, 0x1C, 0x16], splash: [0x1A, 0x14, 0x0F], white: [0xFF, 0xFD, 0xFA], red: [0xFF, 0x45, 0x3A] };
const hex = a => '#' + a.map(v => v.toString(16).padStart(2, '0')).join('');
const PAL = [C.card, C.splash, C.white, C.red];

/* ───── 分析核 ───── */
const segDist = (p, a, b) => {
  const ab = [b[0] - a[0], b[1] - a[1], b[2] - a[2]], ap = [p[0] - a[0], p[1] - a[1], p[2] - a[2]];
  const d2 = ab[0] ** 2 + ab[1] ** 2 + ab[2] ** 2 || 1;
  const t = Math.max(0, Math.min(1, (ap[0] * ab[0] + ap[1] * ab[1] + ap[2] * ab[2]) / d2));
  const q = [a[0] + t * ab[0], a[1] + t * ab[1], a[2] + t * ab[2]];
  return Math.max(Math.abs(p[0] - q[0]), Math.abs(p[1] - q[1]), Math.abs(p[2] - q[2]));
};
const PAIRS = [[0, 1], [0, 2], [0, 3], [1, 2], [1, 3], [2, 3]];
function purity(px, n, anchors = PAL) {
  let bad = 0, maxRes = 0; const worst = [];
  for (let i = 0; i < n; i++) {
    const p = [px[i * 4], px[i * 4 + 1], px[i * 4 + 2]];
    let best = 1e9, bi = '';
    for (const [a, b] of PAIRS) { const d = segDist(p, anchors[a], anchors[b]); if (d < best) { best = d; bi = `${a}-${b}`; } }
    if (best > maxRes) maxRes = best;
    if (best > 2) { bad++; if (worst.length < 3) worst.push({ at: [i % B, (i / B) | 0], rgb: p, res: +best.toFixed(1), seg: bi }); }
  }
  return { bad, pct: +(100 * bad / n).toFixed(2), maxRes: +maxRes.toFixed(1), worst };
}
const cov = (r, g, b) => Math.max(0, Math.min(1, (g - C.card[1]) / (C.white[1] - C.card[1])));
const covAt = (px, x, y) => { const i = (y * B + x) * 4; return cov(px[i], px[i + 1], px[i + 2]); };
function glyphMetrics(px) {
  const cols = new Array(B).fill(0), rows = new Array(B).fill(0);
  for (let y = 0; y < B; y++) for (let x = 0; x < B; x++) {
    const c = covAt(px, x, y);
    if (c > cols[x]) cols[x] = c;
    if (c > rows[y]) rows[y] = c;
  }
  const pure = cols.map((c, x) => [x, c]).filter(([, c]) => c >= 0.99).map(([x]) => x);
  const peaks = [];
  for (let x = 0; x < B; x++) {
    const l = x > 0 ? cols[x - 1] : 0, r = x < B - 1 ? cols[x + 1] : 0, c = cols[x];
    if (c < 0.25 || c < l || c < r || c - Math.min(l, r) < 0.10) continue;
    peaks.push(x);
  }
  const runs = [];
  for (let y = 0; y < B; y++) {
    let s = -1;
    for (let x = 0; x <= B; x++) {
      const on = x < B && covAt(px, x, y) >= 0.5;
      if (on && s < 0) s = x;
      if (!on && s >= 0) { runs.push(x - s); s = -1; }
    }
  }
  runs.sort((a, b) => a - b);
  const ink = rows.map((c, y) => [y, c]).filter(([, c]) => c > 0.1);
  return { pureCols: pure.length, pureAt: pure, peaks: peaks.length, peakX: peaks,
    strokeMedian: runs.length ? runs[runs.length >> 1] : 0, strokeMax: runs.length ? runs[runs.length - 1] : 0,
    inkY: ink.length ? [ink[0][0], ink[ink.length - 1][0]] : null,
    covGte90: +(100 * cols.filter(c => c >= 0.9).length / B).toFixed(1) };
}

/* ───── 磁贴定义（位置全部在前端 JS 里按实测 advance 算，避免半像素错位） ───── */
const EM_A = 10.39, BASE_A = 27.78;
const LH_W = { b19: 19.5, b20: 20.9, b29: 29.76 };
const TILES = [
  { id: 'A-white', kind: 'leg', em: EM_A, text: 'LiveHub', base: BASE_A },
  { id: 'A-2color', kind: 'two', em: EM_A, base: BASE_A },
  { id: 'B19-white', kind: 'leg', em: LH_W.b19 / 1.2886, text: 'LH', base: null },
  { id: 'B20-white', kind: 'leg', em: LH_W.b20 / 1.2886, text: 'LH', base: null },
  { id: 'B29-white', kind: 'leg', em: LH_W.b29 / 1.2886, text: 'LH', base: null },
  { id: 'B29-2color', kind: 'two', em: LH_W.b29 / 1.2886 },
  { id: 'CTL-wrong-red', kind: 'ctl-red', em: EM_A },
  { id: 'CTL-grey-stroke', kind: 'ctl-grey', em: EM_A },
];

const css = `
@font-face{font-family:InterVar;src:url("${FONT}") format('woff2');font-weight:100 900;font-style:normal}
html,body{margin:0;background:#000;color:#ccc;font:11px -apple-system,"PingFang SC",sans-serif}
.tile{position:absolute;width:${B}px;height:${B}px;overflow:hidden;background:${hex(C.card)}}
svg{display:block}
.t{font-family:InterVar;font-weight:600;font-variation-settings:'opsz' 32;font-optical-sizing:none;letter-spacing:-0.02em}
span.m{position:absolute;left:-9999px;top:0;font-family:InterVar;font-weight:600;font-size:100px;
  line-height:1;letter-spacing:-0.02em;white-space:pre;font-optical-sizing:none}
.m32{font-variation-settings:'opsz' 32}.m14{font-variation-settings:'opsz' 14}`;

const tileHtml = TILES.map((t, i) => {
  const x = i % 4, y = (i / 4) | 0;
  const fs = t.em.toFixed(4);
  const white = hex(C.white), red = hex(C.red);
  let body;
  if (t.kind === 'leg') {
    body = `<text class="t" data-tile="${t.id}" x="0" y="0" font-size="${fs}" fill="${white}">${t.text}</text>`;
  } else {
    const hubFill = t.kind === 'ctl-red' ? '#E03127' : red;
    const stroke = t.kind === 'ctl-grey' ? ' stroke="#808080" stroke-width="0.6"' : '';
    body = `<text class="t" data-tile="${t.id}" data-part="live" x="0" y="0" font-size="${fs}" fill="${white}"${stroke}>Live</text>`
      + `<text class="t" data-tile="${t.id}" data-part="hub" x="0" y="0" font-size="${fs}" fill="${hubFill}"${stroke}>Hub</text>`;
  }
  return `<div class="tile" id="${t.id}" style="left:${10 + x * 58}px;top:${10 + y * 58}px"><svg width="${B}" height="${B}">${body}</svg></div>`;
}).join('');

const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>${css}</style></head><body>
<span class="m m32" id="m-livehub">LiveHub</span>
<span class="m m32" id="m-live">Live</span>
<span class="m m32" id="m-hub">Hub</span>
<span class="m m32" id="m-lh">LH</span>
<span class="m m32" id="m-l">L</span>
<span class="m m32" id="m-h">H</span>
<span class="m m14" id="m-livehub14">LiveHub</span>
${tileHtml}
<script>
const TILES = ${JSON.stringify(TILES.map(t => ({ id: t.id, kind: t.kind, em: t.em, base: t.base, text: t.text })))};
const CAP = 0.72754;
window.__fontOk = null; window.__placed = null; window.__err = null; window.__status = null;
function placeNow() {
  if (window.__placed) return;
  window.__fontOk = document.fonts.check('600 48px InterVar');
  window.__status = document.fonts.status;
  const W = id => document.getElementById(id).getBoundingClientRect().width / 100;   // em
  const adv = { livehub: W('m-livehub'), live: W('m-live'), hub: W('m-hub'), lh: W('m-lh'), L: W('m-l'), H: W('m-h'), livehub14: W('m-livehub14') };
  const place = {};
  for (const t of TILES) {
    const nodes = document.querySelectorAll('[data-tile="' + t.id + '"]');
    const base = t.base !== null ? t.base : (48 + CAP * t.em) / 2;
    let x;
    if (t.kind === 'leg') x = (48 - adv[t.text === 'LH' ? 'lh' : 'livehub'] * t.em) / 2;
    else x = (48 - adv.livehub * t.em) / 2;
    for (const n of nodes) {
      n.setAttribute('y', base.toFixed(3));
      // 连续字符串：Live 的 advance 减掉尾部那一个字距，不在元素边界凭空多一道缝
      if (t.kind !== 'leg' && n.dataset.part === 'hub') n.setAttribute('x', (x + (adv.live - 0.02) * t.em).toFixed(3));
      else n.setAttribute('x', x.toFixed(3));
    }
    place[t.id] = { base: +base.toFixed(3), x: +x.toFixed(3) };
  }
  window.__place = place; window.__adv = adv; window.__placed = 1;
}
document.fonts.ready.then(() => { try { placeNow(); } catch (e) { window.__err = String(e); } });
setTimeout(() => { try { placeNow(); window.__fallback = 1; } catch (e) { window.__err = String(e); } }, 600);
</script></body></html>`;
writeFileSync(join(DER, 'bench.html'), html);

/* ───── CDP ───── */
const wait = ms => new Promise(r => setTimeout(r, ms));
const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
  '--force-color-profile=srgb', '--force-device-scale-factor=1', '--allow-file-access-from-files',
  `--remote-debugging-port=${PORT}`, '--window-size=400,320', 'file://' + join(DER, 'bench.html')], { stdio: 'ignore' });
let target = null;
for (let i = 0; i < 80; i++) {
  try { const l = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json(); target = l.find(t => t.type === 'page' && t.webSocketDebuggerUrl); if (target) break; } catch {}
  await wait(120);
}
if (!target) { proc.kill(); throw new Error('Chrome 未就绪'); }
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let id = 0; const pend = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
const send = (method, params = {}) => new Promise(res => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async expr => JSON.parse((await send('Runtime.evaluate', { expression: expr, returnByValue: true })).result.result.value);
await send('Page.enable'); await send('Runtime.enable');
for (let i = 0; i < 60; i++) { if (await ev('JSON.stringify(window.__placed)') === 1) break; await wait(100); }
const fontOk = await ev('JSON.stringify(window.__fontOk)');
const info = await ev(`JSON.stringify({adv:Object.fromEntries(Object.entries(window.__adv).map(([k,v])=>[k,+v.toFixed(4)])),
  place:window.__place, boxes:Object.fromEntries(${JSON.stringify(TILES.map(t => t.id))}.map(i=>{const r=document.getElementById(i).getBoundingClientRect();return [i,{x:Math.round(r.x),y:Math.round(r.y)}]}))})`);

const results = [];
for (const t of TILES) {
  const bx = info.boxes[t.id];
  const shot = await send('Page.captureScreenshot', { format: 'png', clip: { x: bx.x, y: bx.y, width: B, height: B, scale: 1 } });
  const buf = Buffer.from(shot.result.data, 'base64');
  const px = toRGBA(decodePNG(buf));
  results.push({ id: t.id, kind: t.kind, em: +t.em.toFixed(2), purity: purity(px, B * B), glyph: glyphMetrics(px) });
  writeFileSync(join(DER, `${t.id}.png`), buf);
}
ws.close(); proc.kill();

/* ───── 输出 ───── */
const spec = { livehub: 3.6300, livehub14: 3.8473, lh: 1.2886 };
console.log(JSON.stringify({ fontOk, adv: info.adv, spec, place: info.place, results }, null, 1));

console.log('\n【字宽实测 vs 常数（opsz 32 锁死）】');
for (const k of ['livehub', 'livehub14', 'lh']) {
  const v = info.adv[k], s = spec[k];
  console.log(`  ${k.padEnd(10)} ${v.toFixed(4)} em   spec ${s}   Δ ${v - s >= 0 ? '+' : ''}${(v - s).toFixed(4)} (${((v / s - 1) * 100).toFixed(2)}%)`);
}
console.log(`  Live ${info.adv.live.toFixed(4)} / Hub ${info.adv.hub.toFixed(4)} / L ${info.adv.L.toFixed(4)} / H ${info.adv.H.toFixed(4)}`);
console.log(`  L+H−0.02 = ${(info.adv.L + info.adv.H - 0.02).toFixed(4)}  vs LH ${info.adv.lh.toFixed(4)}`);

console.log('\n【磁贴】（48×48 1:1，无缩放）');
for (const r of results) {
  const g = r.glyph, p = r.purity;
  console.log(`  ${r.id.padEnd(16)} em ${String(r.em).padEnd(6)} 纯列 ${String(g.pureCols).padStart(2)} [${g.pureAt.join(',')}]`
    + ` 峰 ${String(g.peaks).padStart(2)} 笔画中位 ${g.strokeMedian} 墨y ${JSON.stringify(g.inkY)} cov≥.9 ${g.covGte90}%`
    + ` | 越界 ${String(p.bad).padStart(4)} (${p.pct}%) 残差 ${p.maxRes}`);
}
console.log('\n【判据自检】');
const ok = (n, c) => console.log(`  ${c ? 'PASS' : 'FAIL'}  ${n}`);
const A2 = results.find(r => r.id === 'A-2color'), B2 = results.find(r => r.id === 'B29-2color');
const wr = results.find(r => r.id === 'CTL-wrong-red'), gy = results.find(r => r.id === 'CTL-grey-stroke');
ok('负对照 双色合法 AA 不越界（A-2color）', A2.purity.bad === 0);
ok('负对照 双色合法 AA 不越界（B29-2color）', B2.purity.bad === 0);
ok('正对照 错红 #E03127 被抓到', wr.purity.bad > 0);
ok('正对照 灰描边 #808080 被抓到', gy.purity.bad > 0);
