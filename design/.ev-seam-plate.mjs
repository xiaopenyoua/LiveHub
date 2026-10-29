// Evidence Collector · 证据图版 ③：五档黑缝（v1.2 判据：一列内的竖直纯黑像素）
// 用法：node design/.ev-seam-plate.mjs  →  design/brand/shots/evidence/ev-icon-03-seam-5sizes.png
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
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9559;

const SNAP = {
  48:  { bx: 11, ty1: 8,  ty2: 40, ax: 36,  ay: 24, bl: 23, br: 36,  by1: 6,  by2: 12 },
  72:  { bx: 17, ty1: 12, ty2: 60, ax: 54,  ay: 36, bl: 34, br: 54,  by1: 9,  by2: 18 },
  96:  { bx: 23, ty1: 16, ty2: 80, ax: 72,  ay: 48, bl: 45, br: 72,  by1: 12, by2: 24 },
  144: { bx: 34, ty1: 24, ty2: 120, ax: 108, ay: 72, bl: 68, br: 108, by1: 18, by2: 36 },
  192: { bx: 45, ty1: 32, ty2: 160, ax: 144, ay: 96, bl: 91, br: 144, by1: 24, by2: 48 },
};
const SOLID = { 48: 4.24, 72: 5.94, 96: 7.64, 144: 11.88, 192: 16.13 };

const img = P => { const im = decodePNG(readFileSync(join(PNGDIR, `ic_launcher_${P}.png`))); return { w: im.w, px: toRGBA(im) }; };

/** 一列里徽章下界→三角上界之间的竖直纯黑像素（v1.2 判据本体） */
function scan(P) {
  const { px } = img(P), n = P * P;
  const W = new Float64Array(n), R = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const r = px[i * 4], g = px[i * 4 + 1], b = px[i * 4 + 2];
    const wl = (r + g + b) / 765, rw = Math.max(Math.abs(255 * wl - r), Math.abs(255 * wl - g), Math.abs(255 * wl - b));
    const rl = (255 * r + 69 * g + 58 * b) / 73150, rr = Math.max(Math.abs(255 * rl - r), Math.abs(69 * rl - g), Math.abs(58 * rl - b));
    if (rw <= rr) W[i] = wl; else R[i] = rl;
  }
  let best = null;
  for (let x = 0; x < P; x++) {
    let bBot = -1, tTop = P;
    for (let y = 0; y < P; y++) {
      if (R[y * P + x] > 0.004 && y > bBot) bBot = y;
      if (W[y * P + x] > 0.004 && y < tTop) tTop = y;
    }
    if (bBot < 0 || tTop >= P || tTop <= bBot) continue;
    let black = 0, dirty = 0;
    for (let y = bBot + 1; y < tTop; y++) {
      const i = (y * P + x) * 4;
      if (px[i] === 0 && px[i + 1] === 0 && px[i + 2] === 0) black++; else dirty++;
    }
    if (!best || black < best.black) best = { x, bBot, tTop, black, dirty };
  }
  return best;
}

/* 裁切 + 最近邻放大，输出每档一张 1:1 放大图 */
const ZOOM_PX = 240;
const tiles = [];
for (const P of [48, 72, 96, 144, 192]) {
  const s = SNAP[P], m = scan(P), im = img(P), padX = Math.round(0.05 * P), padY = Math.round(0.05 * P);
  const x0 = s.bl - padX, x1 = s.br, y0 = s.by1 - padY, y1 = s.by2 + Math.round(SOLID[P]) + padY + 2;
  const cw = x1 - x0, ch = y1 - y0, z = ZOOM_PX / cw;
  const out = Buffer.alloc(cw * ch * 3);
  for (let y = 0; y < ch; y++) for (let x = 0; x < cw; x++) {
    const sx = Math.min(P - 1, x0 + x), sy = Math.min(P - 1, y0 + y), i = (sy * P + sx) * 4, o = (y * cw + x) * 3;
    out[o] = im.px[i]; out[o + 1] = im.px[i + 1]; out[o + 2] = im.px[i + 2];
  }
  const f = join(DER, `crop-${P}.png`);
  writeFileSync(f, encodeRGB(cw, ch, out));
  tiles.push({ P, url: 'file://' + f, cw, ch, z, m, x0, y0 });
}

const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;background:#0a0a0c;color:#e8e8ea;font:12px/1.6 -apple-system,"PingFang SC",sans-serif}
.plate{padding:22px 24px}
.h{font-size:13px;font-weight:600;color:#fff;margin-bottom:4px;letter-spacing:.02em}
.sub{font-size:11px;color:#8a8a94;margin-bottom:16px}
.row{display:flex;gap:16px;align-items:flex-start;flex-wrap:nowrap}
figure{margin:0}
.cap{margin-top:8px;font-size:10.5px;color:#8a8a94;letter-spacing:.02em;line-height:1.55}
.cap b{color:#fff}
.cap s{text-decoration:none;color:#5f5f68}
.rel{position:relative;box-shadow:0 0 0 1px #2a2a30;background:#000}
.rel img{display:block;image-rendering:pixelated}
.hi{position:absolute;background:rgba(58,160,255,.30);box-shadow:inset 0 0 0 1px #3aa0ff}
.vl{position:absolute;top:0;bottom:0;width:1px;background:#3aa0ff}
.note{margin-top:16px;color:#9a9aa4;font-size:11px;line-height:1.9}
.note b{color:#fff}
.note code{color:#c9c9cf;background:#191920;padding:1px 5px}
</style></head><body><div class="plate">
<div class="h">③ 五档黑缝 —— 判据：缝内最窄一列有几个<b>竖直纯黑像素</b>（不是几列黑）</div>
<div class="sub">每格刻度不同：均放大到 ${ZOOM_PX}px 宽以便看清单像素。蓝框 = 最窄列里那段纯黑像素。</div>
<div class="row">
${tiles.map(t => `<figure>
<div class="rel" style="width:${Math.round(t.cw * t.z)}px;height:${Math.round(t.ch * t.z)}px">
<img src="${t.url}" width="${Math.round(t.cw * t.z)}" height="${Math.round(t.ch * t.z)}">
<div class="hi" style="left:${(t.m.x - t.x0) * t.z}px;top:${(t.m.bBot + 1 - t.y0) * t.z}px;width:${t.z}px;height:${t.m.black * t.z}px"></div>
</div>
<figcaption class="cap"><b>${t.P}px</b>　纯黑 <b>${t.m.black}</b><br>
<s>x=${t.m.x}　放大 ${t.z.toFixed(1)}×</s><br>
<s>该列非纯黑 ${t.m.dirty} 个</s></figcaption></figure>`).join('')}
</div>
<div class="note">下限 3。<b>48 档正好 3（x=23），余量 0 像素</b>；72 → 5、96 → 8、144 → 13、192 → 18。
所以「余量 21%」只在法向口径成立（3.10 对 2.56 = 0.08H），换成光栅口径就是零：竖直净空 3.68px 减去抗锯齿吃掉的 0.68px 恰好等于 3。
若按 0.08H 下界画（法向 2.56 → 竖直 3.05），AA 后只剩 2 个纯黑 = 粘连。图中五格的缝内<b>没有任何一个同时含白与红的像素</b>。
<br><code>node design/.ev-seam.mjs</code> · 蓝色框是像素级的，边界取「任意覆盖 α&gt;0」</div>
</div><script>window.__ready=1;</script></body></html>`;
const tmpHtml = join(DER, 'seam-plate.html');
writeFileSync(tmpHtml, html);

/* ── CDP 截图（照抄 design/.shots.mjs 的用法） ── */
const wait = ms => new Promise(r => setTimeout(r, ms));
const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
  '--force-color-profile=srgb', '--force-device-scale-factor=1', '--allow-file-access-from-files',
  `--remote-debugging-port=${PORT}`, '--window-size=1500,600', 'file://' + tmpHtml], { stdio: 'ignore' });
let target = null;
for (let i = 0; i < 80; i++) {
  try {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
    target = list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
    if (target) break;
  } catch { /* not up yet */ }
  await wait(120);
}
if (!target) { proc.kill(); throw new Error('Chrome 未就绪'); }
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let id = 0; const pend = new Map();
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); } };
const send = (method, params = {}) => new Promise(res => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
await send('Page.enable'); await send('Runtime.enable');
await wait(700);
const box = await send('Runtime.evaluate', { expression: 'JSON.stringify({w:document.body.scrollWidth,h:document.body.scrollHeight})', returnByValue: true });
const { w, h } = JSON.parse(box.result.result.value);
await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false });
await wait(250);
const shot = await send('Page.captureScreenshot', { format: 'png', clip: { x: 0, y: 0, width: w, height: h, scale: 1 } });
const out = join(EVI, 'ev-icon-03-seam-5sizes.png');
writeFileSync(out, Buffer.from(shot.result.data, 'base64'));
console.log('写出', out, `${w}×${h}`);
console.log(tiles.map(t => `${t.P}: 纯黑 ${t.m.black} @x=${t.m.x} 脏 ${t.m.dirty} 裁 ${t.cw}x${t.ch} 放大 ${t.z.toFixed(1)}x`).join('\n'));
ws.close(); proc.kill();
