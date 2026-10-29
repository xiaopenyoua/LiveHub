// Evidence Collector · 独立光栅化台
// 目的：不看作者脚本，自己把「交付的 SVG 路径」和「按 02 表格整数坐标重画」分别在目标像素尺寸
// 光栅化，拿去和交付 PNG 逐像素比对；另把 monochrome 层渲成 alpha 图做拓扑检查。
// 用法：node design/.ev-raster.mjs
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG, toRGBA, encodeRGB } from './.ev-png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const ICON = join(ROOT, 'assets/brand/icon');
const EVI = join(ROOT, 'design/brand/shots/evidence');
const TMP = join(EVI, '_ev');
mkdirSync(EVI, { recursive: true }); mkdirSync(TMP, { recursive: true });

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9533;
const SIZES = [48, 72, 96, 144, 192];

/* ---------- 1. 从交付 SVG 里原样抽出 path d（正则，不经任何第三方解析） ---------- */
const grabPaths = (file) => {
  const txt = readFileSync(file, 'utf8');
  const out = [];
  const re = /<path\b[^>]*\bd="([^"]+)"[^>]*>/g;
  let m;
  while ((m = re.exec(txt))) {
    const tag = m[0];
    const fill = (tag.match(/fill="([^"]+)"/) || [, 'none'])[1];
    out.push({ d: m[1], fill });
  }
  return { txt, paths: out };
};
const FG = grabPaths(join(ICON, 'ic_launcher_foreground.svg'));
const MO = grabPaths(join(ICON, 'ic_launcher_monochrome.svg'));

/* ---------- 2. 我自己按 02 §2 表格写的整数几何（独立实现，不 import .icon-geom.mjs） ---------- */
const SNAP = {
  48:  { bx: 11, ty1: 8,  ty2: 40, ax: 36, ay: 24, bl: 23, br: 36, by1: 6,  by2: 12 },
  72:  { bx: 17, ty1: 12, ty2: 60, ax: 54, ay: 36, bl: 34, br: 54, by1: 9,  by2: 18 },
  96:  { bx: 23, ty1: 16, ty2: 80, ax: 72, ay: 48, bl: 45, br: 72, by1: 12, by2: 24 },
  144: { bx: 34, ty1: 24, ty2: 120, ax: 108, ay: 72, bl: 68, br: 108, by1: 18, by2: 36 },
  192: { bx: 45, ty1: 32, ty2: 160, ax: 144, ay: 96, bl: 91, br: 144, by1: 24, by2: 48 },
};
const snapPaths = (P) => {
  const g = SNAP[P];
  const r = (g.by2 - g.by1) / 2, cy = (g.by1 + g.by2) / 2;
  const tri = `M${g.bx},${g.ty1} L${g.bx},${g.ty2} L${g.ax},${g.ay} Z`;
  const pill = `M${g.bl},${cy} A${r},${r} 0 0 1 ${g.bl + r},${g.by1} H${g.br - r} A${r},${r} 0 0 1 ${g.br},${cy}`
    + ` A${r},${r} 0 0 1 ${g.br - r},${g.by2} H${g.bl + r} A${r},${r} 0 0 1 ${g.bl},${cy} Z`;
  return { tri, pill };
};

/* ---------- 3. 版面 ---------- */
const tiles = [];
const add = (id, w, h, body, bg) => tiles.push({ id, w, h, body, bg });

// 3a. 交付 SVG 的路径，裁到 72 视口，按目标像素 1:1 光栅化
for (const P of SIZES) {
  const body = `<svg xmlns="http://www.w3.org/2000/svg" width="${P}" height="${P}" viewBox="18 18 72 72" shape-rendering="geometricPrecision">`
    + `<rect x="18" y="18" width="72" height="72" fill="#000000"/>`
    + FG.paths.map(p => `<path fill="${p.fill}" d="${p.d}"/>`).join('') + `</svg>`;
  add('svg-' + P, P, P, body, '#000');
}
// 3b. 按 02 表格整数坐标重画（应当与交付 PNG 逐像素一致 → 证明是原生光栅而非位图缩放）
for (const P of SIZES) {
  const s = snapPaths(P);
  const body = `<svg xmlns="http://www.w3.org/2000/svg" width="${P}" height="${P}" viewBox="0 0 ${P} ${P}" shape-rendering="geometricPrecision">`
    + `<rect width="${P}" height="${P}" fill="#000000"/>`
    + `<path fill="#FFFFFF" d="${s.tri}"/><path fill="#FF453A" d="${s.pill}"/></svg>`;
  add('snap-' + P, P, P, body, '#000');
}
// 3c. monochrome：白墨铺在纯红底上 → alpha 可由 g 通道精确还原（白+红混合只抬 g/b，不抬 r）
for (const P of [48, 96, 192]) {
  const body = `<svg xmlns="http://www.w3.org/2000/svg" width="${P}" height="${P}" viewBox="18 18 72 72">`
    + `<rect x="18" y="18" width="72" height="72" fill="#FF0000"/>`
    + MO.paths.map(p => `<path fill="#FFFFFF" d="${p.d}"/>`).join('') + `</svg>`;
  add('mono-' + P, P, P, body, '#f00');
}
// 3d. monochrome 全 108 层 @1080（10×，做两块连通性拓扑检查）
{
  const body = `<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" viewBox="0 0 108 108">`
    + `<rect width="108" height="108" fill="#FF0000"/>`
    + MO.paths.map(p => `<path fill="#FFFFFF" d="${p.d}"/>`).join('') + `</svg>`;
  add('mono1080', 1080, 1080, body, '#f00');
}
// 3e. 取证图：monochrome 纯白剪影 on #1C1C1E（48/96/192 + 6× 放大）
{
  const cell = (v, Px, label) => `<div style="display:inline-block;text-align:center;color:#8a8a8e;font:11px Inter,sans-serif;margin-right:34px">
    <div style="background:#1C1C1E;padding:14px;display:inline-block"><svg width="${Px}" height="${Px}" viewBox="18 18 72 72">`
    + MO.paths.map(p => `<path fill="#FFFFFF" d="${p.d}"/>`).join('') + `</svg></div><div style="margin-top:8px">${label}</div></div>`;
  const body = `<div style="padding:26px;background:#0b0b0d">${cell(0, 48, '48 真实')}${cell(0, 96, '96 真实')}${cell(0, 192, '192 真实')}${cell(0, 96 * 3, '96 × 3 放大（看缝）')}</div>`;
  add('shot-mono', 1200, 700, body, '#0b0b0d');
}

/* ---------- 4. 排版：整数偏移，逐块 clip 截图 ---------- */
const PAD = 24, MAXW = 1900;
const pos = []; let x = PAD, y = PAD, rowH = 0;
for (const t of tiles) {
  if (x + t.w + PAD > MAXW) { x = PAD; y += rowH + PAD; rowH = 0; }
  pos.push({ ...t, x, y });
  x += t.w + PAD; rowH = Math.max(rowH, t.h);
}
const PAGE_W = 1920, PAGE_H = Math.max(1080, y + rowH + PAD);

const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;padding:0;background:#101014;overflow:hidden}
.t{position:absolute;font-size:0;line-height:0}
</style></head><body>
${pos.map(t => `<div class="t" style="left:${t.x}px;top:${t.y}px;width:${t.w}px;height:${t.h}px">${t.body}</div>`).join('\n')}
</body></html>`;
const tmpHtml = join(TMP, 'raster.html');
writeFileSync(tmpHtml, html);

const wait = ms => new Promise(r => setTimeout(r, ms));
const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
  '--force-color-profile=srgb', '--force-device-scale-factor=1',
  `--remote-debugging-port=${PORT}`, `--window-size=${PAGE_W},${PAGE_H}`, 'file://' + tmpHtml], { stdio: 'ignore' });

let target = null;
for (let i = 0; i < 60; i++) {
  await wait(250);
  try { const l = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
    target = l.find(t => t.type === 'page' && t.url.includes('raster.html')); if (target) break; } catch {}
}
if (!target) { proc.kill(); throw new Error('连不上 Chrome'); }
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r, { once: true }));
let id = 0; const pend = new Map();
ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } });
const send = (method, params = {}) => new Promise(res => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: PAGE_W, height: PAGE_H, deviceScaleFactor: 1, mobile: false });
await wait(900);

const out = {};
for (const t of pos) {
  const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false,
    clip: { x: t.x, y: t.y, width: t.w, height: t.h, scale: 1 } });
  const img = decodePNG(Buffer.from(r.result.data, 'base64'));
  if (img.w !== t.w || img.h !== t.h) throw new Error(`${t.id} 截图 ${img.w}×${img.h} 期望 ${t.w}×${t.h}`);
  const rgba = toRGBA(img);
  writeFileSync(join(TMP, t.id + '.png'), encodeRGB(t.w, t.h, (() => {
    const b = Buffer.alloc(t.w * t.h * 3);
    for (let i = 0; i < t.w * t.h; i++) { b[i * 3] = rgba[i * 4]; b[i * 3 + 1] = rgba[i * 4 + 1]; b[i * 3 + 2] = rgba[i * 4 + 2]; }
    return b;
  })()));
  out[t.id] = { w: t.w, h: t.h, file: join(TMP, t.id + '.png') };
}

// 取证截图：monochrome on 深灰
{ const t = pos.find(p => p.id === 'shot-mono');
  const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false,
    clip: { x: t.x, y: t.y, width: t.w, height: Math.min(t.h, 400), scale: 1 } });
  writeFileSync(join(EVI, 'ev-icon-07-monochrome.png'), Buffer.from(r.result.data, 'base64'));
}
ws.close(); proc.kill();

console.log(JSON.stringify({
  deliveredSvg: { fgPathCount: FG.paths.length, fgFills: FG.paths.map(p => p.fill), fgViewBox: (FG.txt.match(/viewBox="([^"]+)"/) || [])[1],
    moPathCount: MO.paths.length, moFills: MO.paths.map(p => p.fill), moViewBox: (MO.txt.match(/viewBox="([^"]+)"/) || [])[1],
    fgHasRect: /<rect|<circle|<rect\b/i.test(FG.txt), moHasRect: /<rect|<circle/i.test(MO.txt),
    fgForbidden: ['stroke', 'gradient', 'filter', 'opacity', 'clipPath', 'mask', 'style'].filter(k => FG.txt.includes(k)),
    moForbidden: ['stroke', 'gradient', 'filter', 'opacity', 'clipPath', 'mask', 'style'].filter(k => MO.txt.includes(k)),
    fgPaths: FG.paths.map(p => p.d), moPaths: MO.paths.map(p => p.d) },
  tiles: out,
}, null, 1));
