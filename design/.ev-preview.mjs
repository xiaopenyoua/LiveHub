// Evidence Collector · 预览页缺陷取证（1920×1080，1:1 校验 + 版面实测 + 截图）
// 用法：node design/.ev-preview.mjs
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const EVI = join(ROOT, 'design/brand/shots/evidence');
mkdirSync(EVI, { recursive: true });
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9541;
const PAGE = 'file://' + join(ROOT, 'design/brand/icon-preview.html');

const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
  '--force-color-profile=srgb', '--force-device-scale-factor=1',
  `--remote-debugging-port=${PORT}`, '--window-size=1920,1080', PAGE], { stdio: 'ignore' });
const wait = ms => new Promise(r => setTimeout(r, ms));

let target = null;
for (let i = 0; i < 60; i++) {
  await wait(250);
  try { const l = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
    target = l.find(t => t.type === 'page' && t.url.includes('icon-preview.html')); if (target) break; } catch {}
}
if (!target) { proc.kill(); throw new Error('连不上 Chrome'); }
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r, { once: true }));
let id = 0; const pend = new Map(); const errs = [];
ws.addEventListener('message', e => {
  const m = JSON.parse(e.data);
  if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') errs.push(m.params.args.map(a => a.value || a.description).join(' '));
  if (m.method === 'Runtime.exceptionThrown') errs.push('EXC ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
  if (pend.has(m.id)) { pend.get(m.id)(m); pend.delete(m.id); }
});
const send = (method, params = {}) => new Promise(res => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.result?.exceptionDetails) throw new Error('EVAL ' + JSON.stringify(r.result.exceptionDetails).slice(0, 300));
  return r.result?.result?.value;
};

await send('Page.enable'); await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1920, height: 1080, deviceScaleFactor: 1, mobile: false });
await wait(1500);
await ev('document.fonts.ready.then(()=>true)');

const report = await ev(`(async () => {
  const R = el => { const r = el.getBoundingClientRect(); return {x:+r.x.toFixed(2),y:+r.y.toFixed(2),w:+r.width.toFixed(2),h:+r.height.toFixed(2),r2:+r.right.toFixed(2),b:+r.bottom.toFixed(2)}; };
  const dpr = window.devicePixelRatio;
  const docW = document.documentElement.scrollWidth, docH = document.documentElement.scrollHeight;

  // ① 五档 1:1：<img> 的实际 CSS 尺寸 vs 文件像素尺寸
  const rowA = [...document.querySelectorAll('.specimen .cell')].map(cell => {
    const img = cell.querySelector('img'), plate = cell.querySelector('.plate'), cap = cell.querySelector('figcaption');
    const ri = R(img), rp = R(plate), rc = R(cap);
    return { alt: img.alt, naturalW: img.naturalWidth, naturalH: img.naturalHeight,
      imgCss: [ri.w, ri.h], plateCss: [rp.w, rp.h], is1to1: ri.w === img.naturalWidth && ri.h === img.naturalHeight,
      imgRect: ri, capRect: rc, plateRect: rp,
      capOverlapsImg: !(rc.y >= ri.b || rc.b <= ri.y || rc.x >= ri.r2 || rc.r2 <= ri.x),
      capGapPx: +(rc.y - ri.b).toFixed(2),
      imgBottom: ri.b, imgRight: ri.r2 };
  });

  // ② 五档底边是否同一基线
  const bottoms = [...new Set(rowA.map(c => c.imgBottom))];

  // ③ 所有元素越界/溢出
  const all = [...document.querySelectorAll('.stage *')];
  const overflow = all.filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && (r.right > 1920.5 || r.bottom > 1080.5 || r.x < -0.5 || r.y < -0.5); })
    .map(e => ({ cls: e.className && e.className.baseVal !== undefined ? 'svg:' + e.className.baseVal : (e.className || e.tagName), rect: R(e) }));

  // ④ 版面块矩形 + 块间重叠
  const blocks = { hdr: document.querySelector('.hdr'), rowA: document.querySelector('.rowA'),
    rowB: document.querySelector('.rowB'), foot: document.querySelector('.foot') };
  const bR = Object.fromEntries(Object.entries(blocks).map(([k, e]) => [k, e ? R(e) : null]));
  const keys = Object.keys(bR);
  const overlaps = [];
  for (let i = 0; i < keys.length; i++) for (let j = i + 1; j < keys.length; j++) {
    const a = bR[keys[i]], b = bR[keys[j]];
    const ov = Math.min(a.b, b.b) - Math.max(a.y, b.y);
    if (ov > 0.5) overlaps.push([keys[i], keys[j], +ov.toFixed(2)]);
  }

  // ⑤ TV 桌面模拟：图标与文字是否错位（用 canvas 量文字墨迹 bbox，而不是信 line-box）
  const tv = document.querySelector('.tv');
  const tvImg = tv.querySelector('img'), tvLabel = tv.querySelector('.tvlabel'), tvSub = tv.querySelector('.tvsub');
  const ri = R(tvImg), rl = R(tvLabel);
  const cs = getComputedStyle(tvLabel);
  const cv = document.createElement('canvas'); cv.width = 900; cv.height = 200;
  const cx = cv.getContext('2d');
  cx.font = cs.fontStyle + ' ' + cs.fontWeight + ' ' + cs.fontSize + ' ' + cs.fontFamily;
  if ('letterSpacing' in cx) cx.letterSpacing = cs.letterSpacing;
  const m = cx.measureText('LiveHub');
  const inkTop = rl.y + (rl.h - parseFloat(cs.fontSize)) / 2 + m.actualBoundingBoxAscent * 0 + (parseFloat(cs.fontSize) - m.actualBoundingBoxAscent - m.actualBoundingBoxDescent) / 2 + m.actualBoundingBoxAscent * 0;
  // 直接用浏览器给的行盒中心 + 字体度量换算墨迹中心
  const asc = m.actualBoundingBoxAscent, desc = m.actualBoundingBoxDescent;
  const lineBoxH = parseFloat(cs.fontSize) * 1; // line-height:1
  const baselineY = rl.y + (rl.h - lineBoxH) / 2 + (lineBoxH - (asc + desc)) / 2 + asc;
  const inkCenterY = baselineY - asc / 2 + desc / 2;
  const iconCenterY = ri.y + ri.h / 2;
  const tvSubR = R(tvSub);

  // ⑥ 单色模拟区：几何是否 1:1、缝在显示尺寸上有多宽
  const monos = [...document.querySelectorAll('.theme .mono, .scells .mono')].map(s => {
    const r = R(s);
    return { vb: s.getAttribute('viewBox'), css: [r.w, r.h], pixels: [+s.getAttribute('width'), +s.getAttribute('height')],
      is1to1: r.w === +s.getAttribute('width') && r.h === +s.getAttribute('height'),
      seamPxAtThisSize: +(5.94 * r.w / 72).toFixed(2), rect: r };
  });
  const themePanel = document.querySelector('.theme');
  const themeBg = getComputedStyle(themePanel).backgroundColor;
  const monoFill = getComputedStyle(document.querySelector('.theme .mono path')).fill;

  // ⑦ 字体、console
  const fontOK = document.fonts.check('600 34px Inter');
  const wLiveHub = (() => { const s = document.createElement('span'); s.style.cssText = 'position:absolute;visibility:hidden;font:600 34px Inter;letter-spacing:-.02em'; s.textContent = 'LiveHub'; document.body.appendChild(s); const w = s.getBoundingClientRect().width; s.remove(); return +w.toFixed(2); })();

  return { dpr, docW, docH, viewport: [innerWidth, innerHeight],
    rowA, baselines: bottoms, overflow, blocks: bR, overlaps,
    tv: { img: ri, label: rl, sub: tvSubR, iconCenterY: +iconCenterY.toFixed(2), inkCenterY: +inkCenterY.toFixed(2),
      delta: +(inkCenterY - iconCenterY).toFixed(2), fontAsc: +asc.toFixed(2), fontDesc: +desc.toFixed(2),
      imgLabelHorizontalGap: +(rl.x - ri.r2).toFixed(2),
      labelFont: cs.fontSize + '/' + cs.fontWeight + '/' + cs.letterSpacing },
    monos, themeBg, monoFill, fontOK, wLiveHub };
})()`);

// 截图
const shots = [
  ['ev-icon-10-preview-1920.png', null],
  ['ev-icon-10-preview-rowA.png', { x: 0, y: 150, width: 1200, height: 260, scale: 1 }],
  ['ev-icon-10-preview-tv.png', { x: 72, y: 400, width: 900, height: 320, scale: 1 }],
];
for (const [name, clip] of shots) {
  const p = clip ? { format: 'png', clip, captureBeyondViewport: false } : { format: 'png', captureBeyondViewport: false };
  const r = await send('Page.captureScreenshot', p);
  writeFileSync(join(EVI, name), Buffer.from(r.result.data, 'base64'));
}
// 主题图标区 3× 放大（只为读图，标注为放大示意）
{
  const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false,
    clip: { x: 980, y: 420, width: 900, height: 340, scale: 2 } });
  writeFileSync(join(EVI, 'ev-icon-10-preview-theme-2x.png'), Buffer.from(r.result.data, 'base64'));
}
// 48px 图标 8× 最近邻放大（看黑缝）。在 Node 里做，不用 canvas —— 避免 file:// 污染与插值造假
{
  const { decodePNG, toRGBA, encodeRGB } = await import('./.ev-png.mjs');
  const { readFileSync } = await import('node:fs');
  const im = decodePNG(readFileSync(join(ROOT, 'assets/brand/icon/png/ic_launcher_48.png')));
  const px = toRGBA(im), S = 8, N = 48 * S;
  const out = Buffer.alloc(N * N * 3);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const i = (((y / S) | 0) * 48 + ((x / S) | 0)) * 4, o = (y * N + x) * 3;
    out[o] = px[i]; out[o + 1] = px[i + 1]; out[o + 2] = px[i + 2];
  }
  writeFileSync(join(EVI, 'ev-icon-03-48px-8x-nearest.png'), encodeRGB(N, N, out));
}

console.log(JSON.stringify({ ...report, consoleErrors: errs }, null, 1));
ws.close(); proc.kill();
