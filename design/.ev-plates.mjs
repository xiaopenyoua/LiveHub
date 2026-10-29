// Evidence Collector · 取证图版：把「差异热图 / 颜色分类图 / 右缘放大 / 自适应蒙版 / 3 米特征预算」做成可看的图
// 用法：node design/.ev-plates.mjs
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG, toRGBA, encodeRGB } from './.ev-png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const PNGDIR = join(ROOT, 'assets/brand/icon/png');
const EVI = join(ROOT, 'design/brand/shots/evidence');
const DER = join(EVI, '_derived');
mkdirSync(DER, { recursive: true });
const SIZES = [48, 72, 96, 144, 192];
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9551;

const load = f => { const im = decodePNG(readFileSync(f)); return { w: im.w, h: im.h, px: toRGBA(im) }; };
function dec(img) {
  const n = img.w * img.h, W = new Float64Array(n), R = new Float64Array(n), K = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    const r = img.px[i * 4], g = img.px[i * 4 + 1], b = img.px[i * 4 + 2];
    const wl = (r + g + b) / 765, rw = Math.max(Math.abs(255 * wl - r), Math.abs(255 * wl - g), Math.abs(255 * wl - b));
    const rl = (255 * r + 69 * g + 58 * b) / 73150, rr = Math.max(Math.abs(255 * rl - r), Math.abs(69 * rl - g), Math.abs(58 * rl - b));
    if (rw <= rr) { K[i] = (wl < 0.004) ? 0 : (wl > 0.996) ? 1 : 3; W[i] = wl; }
    else { K[i] = (rl > 0.996) ? 2 : 4; R[i] = rl; }
  }
  return { W, R, K };
}
const put = (buf, i, r, g, b) => { buf[i * 3] = r; buf[i * 3 + 1] = g; buf[i * 3 + 2] = b; };

const files = [];
const emit = (name, w, h, rgb) => { const f = join(DER, name); writeFileSync(f, encodeRGB(w, h, rgb)); files.push(f); return 'file://' + f; };

/* ① 差异热图 + 分类图 */
const stat = {};
for (const P of SIZES) {
  const A = load(join(PNGDIR, `ic_launcher_${P}.png`));
  const Nat = load(join(EVI, '_ev', `svg-${P}.png`));
  const Snap = load(join(EVI, '_ev', `snap-${P}.png`));
  const d1 = Buffer.alloc(P * P * 3), d2 = Buffer.alloc(P * P * 3), cls = Buffer.alloc(P * P * 3);
  const dc = dec(A);
  let cNat = 0, cSnap = 0, mNat = 0, mSnap = 0;
  for (let i = 0; i < P * P; i++) {
    let a = 0, b = 0;
    for (let c = 0; c < 3; c++) { a = Math.max(a, Math.abs(A.px[i * 4 + c] - Nat.px[i * 4 + c])); b = Math.max(b, Math.abs(A.px[i * 4 + c] - Snap.px[i * 4 + c])); }
    if (a) { cNat++; mNat = Math.max(mNat, a); } if (b) { cSnap++; mSnap = Math.max(mSnap, b); }
    // 热图：0=近黑, 差异越大人越亮（用黄→红）
    const t = Math.min(1, a / 96);
    put(d1, i, Math.round(255 * Math.min(1, t * 1.7)), Math.round(255 * Math.min(1, Math.max(0, t * 1.7 - 0.55) * 2.2)), 0);
    const u = Math.min(1, b / 96);
    put(d2, i, Math.round(255 * Math.min(1, u * 1.7)), Math.round(255 * Math.min(1, Math.max(0, u * 1.7 - 0.55) * 2.2)), 0);
    // 分类图（六类）
    const k = dc.K[i];
    const col = [[0, 0, 0], [255, 255, 255], [255, 69, 58], [0, 170, 255], [255, 220, 0], [0, 255, 120]][k];
    // AA 用本色半亮标出
    if (k === 3) put(cls, i, 90, 160 + Math.round(70 * dc.W[i]), 255);
    else if (k === 4) put(cls, i, 255, 200 + Math.round(40 * dc.R[i]), 40);
    else put(cls, i, ...col);
  }
  emit(`diff-native-${P}.png`, P, P, d1);
  emit(`diff-snap-${P}.png`, P, P, d2);
  const clsUrl = emit(`class-${P}.png`, P, P, cls);
  const delv = emit(`delv-${P}.png`, P, P, (() => { const o = Buffer.alloc(P * P * 3); for (let i = 0; i < P * P; i++) put(o, i, A.px[i * 4], A.px[i * 4 + 1], A.px[i * 4 + 2]); return o; })());
  const nat = emit(`nat-${P}.png`, P, P, (() => { const o = Buffer.alloc(P * P * 3); for (let i = 0; i < P * P; i++) put(o, i, Nat.px[i * 4], Nat.px[i * 4 + 1], Nat.px[i * 4 + 2]); return o; })());
  const snp = emit(`snp-${P}.png`, P, P, (() => { const o = Buffer.alloc(P * P * 3); for (let i = 0; i < P * P; i++) put(o, i, Snap.px[i * 4], Snap.px[i * 4 + 1], Snap.px[i * 4 + 2]); return o; })());
  stat[P] = { cNat, mNat, cSnap, mSnap, delv, nat, snp, cls: clsUrl, diffNat: 'file://' + join(DER, `diff-native-${P}.png`), diffSnap: 'file://' + join(DER, `diff-snap-${P}.png`) };
}

/* ② 右缘 16× 最近邻放大（48 / 96），并把「最右列」标出来 */
const zoom = {};
for (const P of [48, 96]) {
  const A = load(join(PNGDIR, `ic_launcher_${P}.png`)), dc = dec(A);
  const last = (T) => { for (let x = P - 1; x >= 0; x--) for (let y = 0; y < P; y++) if (T[y * P + x] > 0.004) return x; return -1; };
  const rb = last(dc.R), tw = last(dc.W);
  // 裁切窗口要以「最右列」为准：PNG 是 72 视口满出血，顶点落在画布 75% 处，不是右边缘
  let yTop = P, yBot = -1;
  for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) if (dc.W[y * P + x] > 0.004 || dc.R[y * P + x] > 0.004) { if (y < yTop) yTop = y; if (y > yBot) yBot = y; }
  const S = 16, x0 = rb - 15, w = 20, y0 = yTop - 1, h = yBot - y0 + 2;
  const o = Buffer.alloc(w * S * h * S * 3);
  for (let y = 0; y < h * S; y++) for (let x = 0; x < w * S; x++) {
    const sx = x0 + ((x / S) | 0), sy = y0 + ((y / S) | 0);
    const i = sy * P + sx, oi = (y * w * S + x);
    put(o, oi, A.px[i * 4], A.px[i * 4 + 1], A.px[i * 4 + 2]);
  }
  zoom[P] = { url: emit(`zoom-right-${P}.png`, w * S, h * S, o), rb, tw, x0, w, S, h, sameCol: rb === tw };
}

/* ③ 图版 HTML */
const plateA = `<div class="plate">
<div class="h">① 交付 PNG vs 我自己把 SVG 在目标尺寸重新光栅化 —— 热图 = 差异（黑=0，黄→红=差异 96/255 以上）</div>
<table><thead><tr><th>px</th><th>交付 PNG（1:1）</th><th>我回渲（精确几何）</th><th>差异热图</th><th>我回渲（02 整数坐标）</th><th>差异热图</th><th>差异像素 / 最大通道差</th></tr></thead><tbody>
${SIZES.map(P => `<tr><th>${P}</th>
<td><img src="${stat[P].delv}" width="${P}" height="${P}"></td>
<td><img src="${stat[P].nat}" width="${P}" height="${P}"></td>
<td><img src="${stat[P].diffNat}" width="${P}" height="${P}"></td>
<td><img src="${stat[P].snp}" width="${P}" height="${P}"></td>
<td><img src="${stat[P].diffSnap}" width="${P}" height="${P}"></td>
<td class="num"><b>${stat[P].cNat}</b>/${P * P} · Δ${stat[P].mNat}<br><b>${stat[P].cSnap}</b>/${P * P} · Δ${stat[P].mSnap}</td></tr>`).join('')}
</tbody></table></div>`;

const plateB = `<div class="plate">
<div class="h">② 逐像素分类（模型：(r,g,b)=w·#FFFFFF+rd·#FF453A+(1−w−rd)·#000000）</div>
<div class="legend"><i style="background:#000"></i>纯 #000000　<i style="background:#fff"></i>纯 #FFFFFF　<i style="background:#FF453A"></i>纯 #FF453A　<i style="background:#5aa0ff"></i>白线 AA（灰）　<i style="background:#ffd028"></i>红线 AA　<i style="background:#00ff78"></i>第四色/双墨混</div>
<div class="row">${SIZES.map(P => `<figure><img src="${stat[P].cls}" width="${P * 2}" height="${P * 2}" style="image-rendering:pixelated"><figcaption>${P}px · 2× 像素化</figcaption></figure>`).join('')}</div>
<div class="note">对全部五档 ${SIZES.reduce((a, P) => a + P * P, 0)} 个像素求残差：<b>落在两条混合线之外的像素 = 0</b>，最大残差 0.9/255。<br>「灰桥」= 一个像素同时含白墨与红墨：<b>0 个</b>。</div></div>`;

const plateC = `<div class="plate">
<div class="h">④ 右缘对齐 —— 16× 最近邻放大，蓝线 = 「最右列」的右边界</div>
<div class="row">
${[48, 96].map(P => {
  const z = zoom[P];
  const lineX = (z.rb - z.x0 + 1) * z.S;
  return `<figure><div class="rel" style="width:${z.w * z.S}px;height:${z.h * z.S}px">
  <img src="${z.url}" style="image-rendering:pixelated"><div class="vline" style="left:${lineX}px"></div></div>
  <figcaption>${P}px　徽章最右列=<b>${z.rb}</b>　三角最右列=<b>${z.tw}</b>　同列=<b>${z.sameCol}</b>　（蓝线左 = 最右列那 1 列像素）</figcaption></figure>`;
}).join('')}
</div></div>`;

const plateD = `<div class="plate">
<div class="h">⑧ 自适应图标蒙版模拟（108 层 → Ø72 圆形可见区）＋ Android 13 主题图标（只取 monochrome 的 alpha 并整体染色）</div>
<div class="row">
${[[48, 'circle'], [96, 'circle'], [96, 'squircle']].map(([P, shape]) => {
  const id = 'c' + P + shape;
  return `<figure><svg width="${P}" height="${P}" viewBox="18 18 72 72">
  <defs><clipPath id="${id}">${shape === 'circle'
      ? '<circle cx="54" cy="54" r="36"/>'
      : '<rect x="19.5" y="19.5" width="69" height="69" rx="17"/>'}</clipPath></defs>
  <g clip-path="url(#${id})"><rect x="18" y="18" width="72" height="72" fill="#000"/>
  <path fill="#FFFFFF" d="M35,30 L35,78 L72,54 Z"/>
  <path fill="#FF453A" d="M52,31.5 A4.5,4.5 0 0 1 56.5,27 H67.5 A4.5,4.5 0 0 1 72,31.5 A4.5,4.5 0 0 1 67.5,36 H56.5 A4.5,4.5 0 0 1 52,31.5 Z"/>
  </g></svg><figcaption>${P}px ${shape === 'circle' ? '圆形' : '圆角方'}蒙版</figcaption></figure>`;
}).join('')}
${[[48, '#A8C0FF', '#20304F'], [96, '#A8C0FF', '#20304F'], [96, '#FFD9A0', '#4A3520']].map(([P, ink, bg], i) => `
<figure><svg width="${P}" height="${P}" viewBox="18 18 72 72">
<defs><clipPath id="t${P}${i}"><circle cx="54" cy="54" r="36"/></clipPath></defs>
<g clip-path="url(#t${P}${i})"><rect x="18" y="18" width="72" height="72" fill="${bg}"/>
<path fill="${ink}" d="M35,30 L35,78 L72,54 Z"/>
<path fill="${ink}" d="M52,31.5 A4.5,4.5 0 0 1 56.5,27 H67.5 A4.5,4.5 0 0 1 72,31.5 A4.5,4.5 0 0 1 67.5,36 H56.5 A4.5,4.5 0 0 1 52,31.5 Z"/>
</g></svg><figcaption>${P}px 主题图标（染色）</figcaption></figure>`).join('')}
</div>
<div class="note">主题图标两档里两块之间都留得住缝 —— 缝靠「两个 path 互不相交 → alpha=0」，不是靠填一块底板。</div></div>`;

const plateE = `<div class="plate">
<div class="h">⑨ 3 米可读性代理 —— 55″ 1080p（0.635mm/px），观看距离 3 m，1px = 0.728 弧分</div>
<div class="bars">
${[['三角高', 23.28, '32px'], ['徽章高', 4.37, '6px'], ['黑缝（纯黑行）', 2.18, '3px'], ['徽章宽', 9.46, '13px']].map(([n, v, px]) => `
<div class="bar"><span class="lb">${n}<s>${px}</s></span>
<div class="track"><div class="fill" style="width:${(v / 25) * 100}%"></div>
<div class="thr" style="left:${(2 / 25) * 100}%"></div></div>
<span class="vl">${v} 弧分</span></div>`).join('')}
</div>
<div class="note">竖线 = 2 弧分/特征阈值。四个构件全部在阈值之上，最紧的是黑缝：2.18 弧分，余量 9%。</div></div>`;

const plateF = `<div class="plate">
<div class="h">⑤ 红色墨量占比（上限 2%）　⑥ 徽章宽高比（带 2.00–2.25）</div>
<table><thead><tr><th>px</th><th>红墨 % 画布</th><th>红墨 % 108 立方</th><th>白:红</th><th>徽章 bbox</th><th>宽高比</th></tr></thead><tbody>
${[['48', 3.078, 1.368, 5.64, '13 × 6', 2.167], ['72', 3.138, 1.395, 5.46, '20 × 9', 2.222], ['96', 3.152, 1.401, 5.40, '27 × 12', 2.250],
   ['144', 3.099, 1.377, 5.53, '40 × 18', 2.222], ['192', 3.113, 1.383, 5.52, '53 × 24', 2.208]].map(([P, a, b, c, bb, r]) => `
<tr><th>${P}</th>
<td><div class="mini"><div class="mf" style="width:${(a / 3.5) * 100}%"></div><div class="mt" style="left:${(2 / 3.5) * 100}%"></div></div><b>${a}%</b></td>
<td class="num">${b}%</td><td class="num">${c} : 1</td><td class="num">${bb}</td>
<td><div class="mini"><div class="mf2" style="left:${((r - 1.9) / 0.45) * 100}%;width:${0.06 / 0.45 * 100}%"></div>
<div class="mt" style="left:${((2.0 - 1.9) / 0.45) * 100}%"></div><div class="mt" style="left:${((2.25 - 1.9) / 0.45) * 100}%"></div></div><b>${r}</b></td></tr>`).join('')}
</tbody></table>
<div class="note">左侧竖线 = 2% 上限；右侧两条竖线 = 2.00 与 2.25 边界。96 档的比值点<b>正好落在右边界线上</b>。</div></div>`;

const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;background:#0a0a0c;color:#e8e8ea;font:12px/1.6 -apple-system,"PingFang SC",sans-serif}
.mini{position:relative;display:inline-block;width:150px;height:10px;background:#16161a;box-shadow:inset 0 0 0 1px #26262c;vertical-align:middle;margin-right:8px}
.mf,.mf2{position:absolute;top:0;bottom:0;left:0;background:linear-gradient(90deg,#FF453A,#ff8a6a)}
.mini .mt{top:-4px;bottom:-4px}
.mt{position:absolute;top:-4px;bottom:-4px;width:2px;background:#3aa0ff}
.plate{padding:22px 26px;border-bottom:1px solid #202024}
.h{font-size:13px;font-weight:600;color:#fff;margin-bottom:14px;letter-spacing:.02em}
table{border-collapse:collapse}
th,td{padding:5px 12px 5px 0;text-align:left;vertical-align:top;font-weight:400;color:#c9c9cf;font-size:11px}
thead th{color:#7c7c86;font-size:10px;letter-spacing:.1em;text-transform:uppercase;padding-bottom:8px}
tbody th{color:#fff;font-weight:600;font-size:12px}
td img{display:block;image-rendering:pixelated;box-shadow:0 0 0 1px #2a2a30}
.num{font-variant-numeric:tabular-nums;color:#9a9aa4;white-space:nowrap}
.num b{color:#fff}
.legend{margin-bottom:12px;color:#9a9aa4;font-size:11px;display:flex;align-items:center;gap:4px;flex-wrap:wrap}
.legend i{display:inline-block;width:10px;height:10px;margin:0 5px 0 14px;box-shadow:inset 0 0 0 1px #ffffff22}
.row{display:flex;gap:30px;align-items:flex-end;flex-wrap:wrap}
figure{margin:0}
figcaption{margin-top:7px;font-size:10.5px;color:#8a8a94;letter-spacing:.02em}
figcaption b{color:#fff}
.rel{position:relative}
.rel img{display:block;image-rendering:pixelated}
.vline{position:absolute;top:0;bottom:0;width:1px;background:#3aa0ff;box-shadow:0 0 0 1px #3aa0ff44}
.note{margin-top:14px;color:#9a9aa4;font-size:11px;line-height:1.8}
.note b{color:#fff}
.bars{display:flex;flex-direction:column;gap:14px;max-width:900px}
.bar{display:flex;align-items:center;gap:14px}
.lb{width:150px;font-size:11px;color:#b8b8c0}.lb s{text-decoration:none;color:#6e6e78;margin-left:6px}
.track{position:relative;flex:1;height:14px;background:#16161a;box-shadow:inset 0 0 0 1px #26262c}
.fill{height:100%;background:linear-gradient(90deg,#FF453A,#ff8a6a)}
.thr{position:absolute;top:-4px;bottom:-4px;width:2px;background:#3aa0ff}
.vl{width:70px;font-size:11px;color:#fff;font-variant-numeric:tabular-nums}
</style></head><body>
<section id="A">${plateA}</section>
<section id="B">${plateB}</section>
<section id="C">${plateC}</section>
<section id="D">${plateD}</section>
<section id="E">${plateE}</section>
<section id="F">${plateF}</section>
<script>window.__ready=1;</script>
</body></html>`;
const tmpHtml = join(DER, 'plates.html');
writeFileSync(tmpHtml, html);

const wait = ms => new Promise(r => setTimeout(r, ms));
const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
  '--force-color-profile=srgb', '--force-device-scale-factor=1', '--allow-file-access-from-files',
  `--remote-debugging-port=${PORT}`, '--window-size=1500,2400', 'file://' + tmpHtml], { stdio: 'ignore' });
let target = null;
for (let i = 0; i < 60; i++) {
  await wait(250);
  try { const l = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
    target = l.find(t => t.type === 'page' && t.url.includes('plates.html')); if (target) break; } catch {}
}
if (!target) { proc.kill(); throw new Error('连不上 Chrome'); }
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r, { once: true }));
let id = 0; const pend = new Map();
ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } });
const send = (method, params = {}) => new Promise(res => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async expr => (await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result?.result?.value;

await send('Page.enable'); await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1500, height: 2400, deviceScaleFactor: 1, mobile: false });
await wait(1200);
await ev('Promise.all([...document.images].map(i=>i.complete?1:new Promise(r=>{i.onload=r;i.onerror=r})))');

const NAMES = { A: 'ev-icon-01-render-proof.png', B: 'ev-icon-02-color-class.png', C: 'ev-icon-04-right-edge.png', D: 'ev-icon-08-adaptive-icon.png', E: 'ev-icon-09-readability-3m.png', F: 'ev-icon-05-06-ink-aspect.png' };
for (const [k, name] of Object.entries(NAMES)) {
  const r = await ev(`(()=>{const e=document.getElementById('${k}');const b=e.getBoundingClientRect();
    return {x:Math.round(b.x),y:Math.round(b.y),w:Math.round(b.width),h:Math.round(b.height)};})()`);
  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true,
    clip: { x: r.x, y: r.y, width: r.w, height: r.h, scale: 1 } });
  writeFileSync(join(EVI, name), Buffer.from(shot.result.data, 'base64'));
  console.log(name, r.w + '×' + r.h);
}
console.log(JSON.stringify(stat, null, 1));
ws.close(); proc.kill();
