// v2 实测：全部从「已落盘的文件」量，不读构建时的内存。
//   ① 重新光栅化交付的 SVG 母版（消费方拿到的东西）→ 量墨迹
//   ② 解码交付的 PNG → 量同一批数 + md5 + 圆角 + 墨色纯度
//   ③ 打开 design/splash.html?canvas=1&still=1 → 量 1920 定稿帧 + 四处取色
// 输出：design/brand/shots/v2-measure.json
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG } from './.png.mjs';
import { openPage } from './.v2-cdp.mjs';
import { PALETTE, GEOMS, byKey, WM, KERN, CAP_EM, INK_EM, iconSvg } from './.v2-geom.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const V2 = join(ROOT, 'assets/brand/v2');
const SHOTS = join(ROOT, 'design/brand/shots');
const TMP = join(HERE, '.v2-tmp');
mkdirSync(TMP, { recursive: true }); mkdirSync(SHOTS, { recursive: true });

const hex = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const P = { icon: hex(PALETTE.icon), app: hex(PALETTE.splash), live: hex(PALETTE.live), hub: hex(PALETTE.hub) };
const maxc = c => Math.max(c[0], c[1], c[2]);
const md5 = b => createHash('md5').update(b).digest('hex');

/* ---------- 采样器：alpha 先合成到 over 上，量到的一定是「屏上看到的色」 ---------- */
function sampler(img, over) {
  return (x, y) => {
    const i = (y * img.w + x) * img.ch;
    if (img.ch === 4) {
      const a = img.data[i + 3] / 255;
      return [img.data[i] * a + over[0] * (1 - a), img.data[i + 1] * a + over[1] * (1 - a), img.data[i + 2] * a + over[2] * (1 - a)];
    }
    return [img.data[i], img.data[i + 1], img.data[i + 2]];
  };
}
/** 相对底板色的覆盖率估计：墨色（白/红）的最大通道都是 255，所以一个式子够用 */
const cov = (px, plate) => Math.max(0, Math.min(1, (maxc(px) - maxc(plate)) / (255 - maxc(plate))));

/* ---------- 字标度量 ---------- */
function measureWordmark(img, { plate, over = null }) {
  const at = sampler(img, over || plate);
  const W = img.w, H = img.h;
  let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
  const A = [];                                        // 覆盖率缓存
  for (let y = 0; y < H; y++) {
    const row = new Float32Array(W); A.push(row);
    for (let x = 0; x < W; x++) {
      const c = cov(at(x, y), plate); row[x] = c;
      if (c >= 0.5) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    }
  }
  if (x1 < 0) return null;

  const colCov = x => { let s = 0, n = 0; for (let y = y0; y <= y1; y++) { s += A[y][x]; n++; } return s / n; };
  const rowCov = (y, xa, xb) => { let s = 0, n = 0; for (let x = xa; x <= xb; x++) { s += A[y][x]; n++; } return s / n; };

  let peak = 0;
  for (let x = x0; x <= x1; x++) peak = Math.max(peak, colCov(x));
  const left = x0 + (1 - colCov(x0) / peak);
  const right = x1 + colCov(x1) / peak;

  // cap 顶 / baseline：只取 L 的竖干（最左边一列实心笔画），避开 b/i 的升部与点
  const stem = Math.max(2, Math.round(0.02 * (x1 - x0) + 2));
  const xa = Math.ceil(left) + 1, xb = Math.min(x1, xa + stem);
  let top = -1, bot = -1, rowPeak = 0;
  for (let y = y0; y <= y1; y++) { const c = rowCov(y, xa, xb); if (c >= 0.5) { if (top < 0) top = y; bot = y; } rowPeak = Math.max(rowPeak, c); }
  const capTop = top + (1 - rowCov(top, xa, xb) / rowPeak);
  const baseline = bot + rowCov(bot, xa, xb) / rowPeak;

  // 红墨首列：R−G ≥ 60 即为红（白 5 / 红 186）
  let redX = -1, redPeak = 0;
  const redCov = x => {
    let s = 0;
    for (let y = y0; y <= y1; y++) { const p = at(x, y); if (p[0] - p[1] >= 60) s += A[y][x]; }
    return s;
  };
  const rc = [];
  for (let x = 0; x < W; x++) rc.push(redCov(x));
  for (let x = 0; x < W; x++) redPeak = Math.max(redPeak, rc[x]);
  for (let x = 0; x < W; x++) if (rc[x] > 0.02 * redPeak) { redX = x + (1 - rc[x] / Math.max(rc[x], rc[x + 1] || 0.0001) * 0) ; break; }
  // 用覆盖率做子像素修正
  redX = (() => { let i = 0; while (i < W && rc[i] <= 0.02 * redPeak) i++; return i + (1 - Math.min(1, rc[i] / fullCol(rc, redPeak))); })();

  return {
    ink: { x0: +left.toFixed(3), x1: +right.toFixed(3), w: +(right - left).toFixed(3) },
    cap: { top: +capTop.toFixed(3), baseline: +baseline.toFixed(3), h: +(baseline - capTop).toFixed(3) },
    centerX: +((left + right) / 2).toFixed(3),
    redX0: +redX.toFixed(3),
    boundaryPct: +(100 * (redX - left) / (right - left)).toFixed(3),
    inkPeak: +peak.toFixed(3),
  };
}
function fullCol(arr, peak) { let m = 0; for (const v of arr) if (v > m * 0.98 && v < peak + 1e-6) m = Math.max(m, v); return m || peak; }

/* ---------- 圆角：用 alpha 面积积分，比找切点稳（圆角矩形面积 = WH − (4−π)r²） ---------- */
function cornerRadius(img, plate, over) {
  let area = 0;
  for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) {
    if (img.ch === 4) area += img.data[(y * img.w + x) * 4 + 3] / 255;
    else { const p = sampler(img, over)(x, y); area += Math.max(0, Math.min(1, 1 - Math.abs(maxc(p) - maxc(plate)) / Math.max(1, maxc(plate) - maxc(over) + 1e-9)) ) > 0.5 ? 1 : 0; }
  }
  const A = img.w * img.h;
  if (A - area <= 0) return { area: A, r: 0 };
  return { area: +area.toFixed(1), r: +Math.sqrt((A - area) / (4 - Math.PI)).toFixed(3) };
}

/* ---------- 四色封闭：每个像素必须落在任意两个令牌的连线上 ---------- */
function segDist(p, a, b) {
  const ab = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
  const ap = [p[0] - a[0], p[1] - a[1], p[2] - a[2]];
  const L = ab[0] ** 2 + ab[1] ** 2 + ab[2] ** 2;
  let t = L ? (ap[0] * ab[0] + ap[1] * ab[1] + ap[2] * ab[2]) / L : 0;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(ap[0] - ab[0] * t, ap[1] - ab[1] * t, ap[2] - ab[2] * t);
}
function paletteAudit(img, over) {
  const toks = Object.values(P), at = sampler(img, over);
  const bad = new Map(); let n = 0;
  for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) {
    const p = at(x, y);
    let best = 1e9;
    for (let i = 0; i < toks.length; i++) for (let j = i + 1; j < toks.length; j++) best = Math.min(best, segDist(p, toks[i], toks[j]));
    if (best > 2.5) {
      n++; const k = p.map(v => Math.round(v)).join(',');
      bad.set(k, (bad.get(k) || 0) + 1);
    }
  }
  return { offPalette: n, worst: [...bad.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5) };
}

/* ================= 开始实测 ================= */
const R = { _what: 'v2 资产与开屏实测（全部读自已落盘文件）', when: new Date().toISOString(), geom: {}, fonts: {}, assets: [], splash: null, legibility: null };

for (const g of GEOMS) {
  R.geom[g.key] = {
    W: g.W, H: g.H, r: g.r, F: +g.F.toFixed(4), cap: +g.cap.toFixed(3), inkW: +g.inkW.toFixed(3),
    inkLeft: +g.inkLeft.toFixed(3), inkRight: +g.inkRight.toFixed(3), capTop: +g.capTop.toFixed(3),
    baseline: +g.baseline.toFixed(3), redX0: +g.redX0.toFixed(3), margins: +g.margins.toFixed(3),
  };
}

/* ---- ① SVG 母版 → 重新光栅化 ---- */
const svgOf = k => readFileSync(join(V2, 'icon', k === 'banner' ? 'banner-16x9.svg' : 'square.svg'), 'utf8');
const rasterPage = (items) => `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;padding:0;background:transparent;overflow:hidden}div{position:absolute;left:0;font-size:0;line-height:0}
</style></head><body>
${items.map(it => `<div style="top:${it.y}px;width:${it.w}px;height:${it.h}px">${svgOf(it.k).replace(
  /width="\d+" height="\d+"/, `width="${it.w}" height="${it.h}"`)}</div>`).join('\n')}
</body></html>`;

const items = [];
let yy = 0;
for (const k of ['banner', 'square']) {
  const g = byKey(k);
  items.push({ k, w: g.W, h: g.H, y: yy, scale: 1 });  yy += g.H + 24;
  const up = k === 'banner' ? 2 : 1;                    // banner 放大 2× 再量，精度翻倍
  if (up !== 1) { items.push({ k, w: g.W * up, h: g.H * up, y: yy, scale: up, big: true }); yy += g.H * up + 24; }
}
writeFileSync(join(TMP, 'verify-raster.html'), rasterPage(items));

const p1 = await openPage({ file: join(TMP, 'verify-raster.html'), port: 9541, width: 1400, height: Math.min(yy + 24, 4000) });
await p1.send('Emulation.setDeviceMetricsOverride', { width: 1400, height: yy + 24, deviceScaleFactor: 1, mobile: false });
await p1.send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
await new Promise(r => setTimeout(r, 400));
for (const it of items) {
  const img = await p1.shoot({ x: 0, y: it.y, w: it.w, h: it.h });
  const m = measureWordmark(img, { plate: P.icon });
  const cr = it.scale === 1 ? cornerRadius(img, P.icon, P.icon) : null;
  const g = byKey(it.k);
  const tag = it.scale === 1 ? it.k : it.k + '@' + it.scale + 'x';
  const sc = it.scale;
  R.assets.push({
    id: `svg:${tag}`, from: 'shipped SVG',
    inkW: +(m.ink.w / sc).toFixed(3), inkW_spec: +g.inkW.toFixed(3),
    centerX: +(m.centerX / sc).toFixed(3), centerX_spec: g.W / 2,
    capTop: +(m.cap.top / sc).toFixed(3), baseline: +(m.cap.baseline / sc).toFixed(3),
    cap: +(m.cap.h / sc).toFixed(3), cap_spec: +g.cap.toFixed(3),
    boundaryPct: m.boundaryPct, redX0: +(m.redX0 / sc).toFixed(3), redX0_spec: +g.redX0.toFixed(3),
    r_measured: cr ? cr.r : null, r_spec: cr ? g.r : null,
  });
}
await p1.close();

/* ---- ② 交付的 PNG ---- */
const FILES = ['banner-640', 'banner-480', 'banner-320', 'banner-240', 'banner-160',
  'square-512', 'square-192', 'square-144', 'square-96', 'square-72', 'square-48'];
R.png = [];
for (const f of FILES) {
  const p = join(V2, 'icon/png', f + '.png');
  if (!existsSync(p)) continue;
  const buf = readFileSync(p);
  const img = decodePNG(buf);
  const kind = f.startsWith('banner') ? 'banner' : 'square';
  const g = byKey(kind);
  const px = Number(f.split('-')[1]);
  const k = px / g.W;                                  // 该档相对设计尺寸的缩放
  const m = measureWordmark(img, { plate: P.icon });
  const cr = img.w * img.h > 0 ? cornerRadius(img, P.icon, P.icon) : null;
  R.png.push({
    file: f + '.png', bytes: buf.length, md5: md5(buf), size: `${img.w}×${img.h}`,
    inkW: +(m.ink.w / k).toFixed(3), inkW_atSize: +m.ink.w.toFixed(2), inkW_spec: +g.inkW.toFixed(3),
    cap: +(m.cap.h / k).toFixed(3), cap_spec: +g.cap.toFixed(3),
    boundaryPct: m.boundaryPct, r_measured: +(cr.r / k).toFixed(3), r_spec: g.r,
    inkPeak: m.inkPeak,
  });
  if (img.w * img.h > 5000) R.assets.push({ id: `png:${f}`, from: 'shipped PNG', palette: paletteAudit(img, P.icon) });
}

/* ---- ③ 开屏定稿帧 ---- */
{
  const file = join(ROOT, 'design/splash.html') + '?canvas=1&still=1';
  const p2 = await openPage({ file, port: 9542, width: 1920, height: 1080, readySelector: '#wm' });
  await p2.eval(`window.__splash.ready`);
  await new Promise(r => setTimeout(r, 300));
  const img = await p2.shoot({ x: 0, y: 0, w: 1920, h: 1080 });
  const g = byKey('splash');
  const at = sampler(img, P.app);
  const m = measureWordmark(img, { plate: P.icon, over: P.app });
  const cr = cornerRadius(img, P.icon, P.app);

  // 卡片 bbox：与 app 底「不同」的行列（容差 2，两色差 9/8/7，够分）
  let cx0 = 1e9, cy0 = 1e9, cx1 = -1, cy1 = -1;
  for (let y = 0; y < 1080; y++) for (let x = 0; x < 1920; x++) {
    const q = at(x, y);
    if (Math.abs(q[0] - P.app[0]) + Math.abs(q[1] - P.app[1]) + Math.abs(q[2] - P.app[2]) > 4) {
      if (x < cx0) cx0 = x; if (x > cx1) cx1 = x; if (y < cy0) cy0 = y; if (y > cy1) cy1 = y;
    }
  }
  // 四处取色：找「恰为令牌值」的像素，不靠猜坐标
  const find = (c, y) => { for (let x = 0; x < 1920; x++) { const q = at(x, y); if (q[0] === c[0] && q[1] === c[1] && q[2] === c[2]) return { x, y, rgb: q }; } return null; };
  const rowMid = Math.round((m.cap.top + m.cap.baseline) / 2);
  const liveAt = find(P.live, rowMid), hubAt = find(P.hub, rowMid);
  const doc = await p2.eval(`JSON.stringify(window.__splash)`);

  const dev = await p2.eval(`(()=>{const t=document.getElementById('wmg').getAttribute('transform');return t})()`);
  R.splash = {
    frame: '1920×1080',
    domTransform: dev,
    domInk: JSON.parse(doc).wmInk,
    inkW: +m.ink.w.toFixed(3), inkW_spec: +g.inkW.toFixed(3),
    inkX0: +m.ink.x0.toFixed(3), inkX1: +m.ink.x1.toFixed(3),
    centerX: +m.centerX.toFixed(3),
    capTop: +m.cap.top.toFixed(3), capTop_spec: +(135 + g.capTop).toFixed(3),
    baseline: +m.cap.baseline.toFixed(3), baseline_spec: +(135 + g.baseline).toFixed(3),
    cap: +m.cap.h.toFixed(3), cap_spec: +g.cap.toFixed(3),
    redX0: +m.redX0.toFixed(3), boundaryPct: m.boundaryPct,
    card: { x0: cx0, y0: cy0, x1: cx1, y1: cy1, w: cx1 - cx0 + 1, h: cy1 - cy0 + 1 },
    cardSpec: { x0: 240, y0: 135, x1: 1679, y1: 944 },
    r_measured: cr.r, r_spec: g.r,
    color: {
      appBg: { want: PALETTE.splash, got: at(10, 10) },
      cardBg: { want: PALETTE.icon, got: at(960, 200) },
      liveInk: { want: PALETTE.live, got: liveAt && liveAt.rgb, at: liveAt && { x: liveAt.x, y: liveAt.y } },
      hubInk: { want: PALETTE.hub, got: hubAt && hubAt.rgb, at: hubAt && { x: hubAt.x, y: hubAt.y } },
    },
    palette: paletteAudit(img, P.app),
    md5: md5(Buffer.from(img.data)),
  };
  writeFileSync(join(SHOTS, 'v2-splash-1920.png'), readFileSync(join(SHOTS, 'v2-splash-1920.png')));
  await p2.close();
}

/* ---- ④ 48px 的诚实结论 ---- */
{
  const g = byKey('square');
  const f48 = R.png.find(x => x.file === 'square-48.png');
  const f96 = R.png.find(x => x.file === 'square-96.png');
  const per = (inkW, n = 7) => inkW / n;
  const mm = px => px * 0.635;                       // 55" 1080p，3m 处 1px ≈ 0.635mm（主理人口径）
  const arcmin = px => Math.atan(mm(px) / 3000) * 180 / Math.PI * 60;
  R.legibility = {
    _basis: '1px = 0.635mm（主理人给的 55"/1080p/3m 口径），主理人可读下限 5 弧分',
    capEm: +CAP_EM.toFixed(6), inkEm: +INK_EM.toFixed(6), stem: 'Inter 600 竖干 = 267/1490 = 0.1792 cap',
    at48: {
      inkW: f48.inkW, inkW_px: f48.inkW_atSize, perLetter: +per(f48.inkW_atSize).toFixed(3),
      cap: f48.cap, stemPx: +(f48.cap * 267 / 1490).toFixed(3),
      mm: +mm(per(f48.inkW_atSize)).toFixed(2), arcmin: +arcmin(per(f48.inkW_atSize)).toFixed(2),
      inkPeak: f48.inkPeak, r: f48.r_measured,
    },
    at96: {
      inkW: f96.inkW, inkW_px: f96.inkW_atSize, perLetter: +per(f96.inkW_atSize).toFixed(3),
      cap: f96.cap, stemPx: +(f96.cap * 267 / 1490).toFixed(3),
      arcmin: +arcmin(per(f96.inkW_atSize)).toFixed(2), inkPeak: f96.inkPeak,
    },
    at512: { inkW: R.png.find(x => x.file === 'square-512.png').inkW, inkPeak: R.png.find(x => x.file === 'square-512.png').inkPeak },
  };
}

writeFileSync(join(SHOTS, 'v2-measure.json'), JSON.stringify(R, null, 1));

/* ================= 打印 ================= */
const row = a => { for (const [k, v] of Object.entries(a)) console.log('  ' + k.padEnd(22) + (typeof v === 'object' ? JSON.stringify(v) : v)); };
console.log('=== ① 交付 SVG 母版重新光栅化 ===');
for (const a of R.assets.filter(x => x.from === 'shipped SVG')) {
  console.log(`\n[${a.id}]`);
  row({
    墨迹宽: `${a.inkW} (规格 ${a.inkW_spec}, ${(100 * (a.inkW / a.inkW_spec - 1)).toFixed(2)}%)`,
    中心x: `${a.centerX} (规格 ${a.centerX_spec})`,
    cap顶: `${a.capTop}  baseline ${a.baseline}  cap 高 ${a.cap} (规格 ${a.cap_spec})`,
    红墨首列x: `${a.redX0} (规格 ${a.redX0_spec})   分界 ${a.boundaryPct}%`,
    圆角: a.r_measured === null ? '—' : `${a.r_measured} (规格 ${a.r_spec})`,
  });
}
console.log('\n=== ② 交付 PNG ===');
console.log('文件             尺寸      md5                                墨宽@尺寸  墨宽/设计   分界%   圆角     墨色纯度');
for (const a of R.png) {
  console.log(a.file.padEnd(17), a.size.padEnd(9), a.md5, String(a.inkW_atSize).padStart(8),
    String(a.inkW).padStart(9), String(a.boundaryPct).padStart(8), String(a.r_measured).padStart(8), String(a.inkPeak).padStart(7));
}
console.log('\n=== ③ 开屏 1920 定稿帧 ===');
row({
  transform: R.splash.domTransform,
  DOM墨迹: JSON.stringify(R.splash.domInk),
  像素墨迹宽: `${R.splash.inkW} (规革 ${R.splash.inkW_spec})`.replace('规革', '规格'),
  中心: R.splash.centerX,
  cap顶: `${R.splash.capTop} (规格 ${R.splash.capTop_spec})`,
  基线: `${R.splash.baseline} (规格 ${R.splash.baseline_spec})`,
  cap高: `${R.splash.cap} (规格 ${R.splash.cap_spec})`,
  红墨首列: `${R.splash.redX0}  分界 ${R.splash.boundaryPct}%`,
  卡片box: `${R.splash.card.x0}–${R.splash.card.x1} × ${R.splash.card.y0}–${R.splash.card.y1}（${R.splash.card.w}×${R.splash.card.h}）`,
  卡片圆角: `${R.splash.r_measured} (规格 ${R.splash.r_spec})`,
});
for (const [k, v] of Object.entries(R.splash.color)) {
  console.log('  ' + k.padEnd(10) + '要 ' + v.want + '  实得 ' + (v.got ? `rgb(${v.got.map(n => Math.round(n)).join(',')})` + (v.at ? ` @${v.at.x},${v.at.y}` : '') : '未找到'));
}
console.log('  四色封闭：' + JSON.stringify(R.splash.palette));
console.log('\n=== ④ 最小档可读性 ===');
row({ 48: JSON.stringify(R.legibility.at48), 96: JSON.stringify(R.legibility.at96), 512: JSON.stringify(R.legibility.at512) });
console.log('\n→ design/brand/shots/v2-measure.json');
