// Evidence Collector · 缝的第三套口径（v1.2）：一列内竖直纯黑像素数 + 实体最短距 + 竖直净空
// 三条互不依赖的路径：
//   A 交付 PNG 逐像素：最窄一列里有几个竖直纯黑像素（判据本体）
//   B 解析：圆心到斜边距离 − r（无像素、无阈值）
//   C 10× 几何光栅 + BFS 距离变换（不复用任何 PNG，纯解析点内测试）
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG, toRGBA } from './.ev-png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const PNGDIR = join(ROOT, 'assets/brand/icon/png');
const SIZES = [48, 72, 96, 144, 192];

// 02 §2 整数几何（自己抄的，不 import 作者脚本）
const SNAP = {
  48:  { bx: 11, ty1: 8,  ty2: 40, ax: 36,  ay: 24, bl: 23, br: 36,  by1: 6,  by2: 12 },
  72:  { bx: 17, ty1: 12, ty2: 60, ax: 54,  ay: 36, bl: 34, br: 54,  by1: 9,  by2: 18 },
  96:  { bx: 23, ty1: 16, ty2: 80, ax: 72,  ay: 48, bl: 45, br: 72,  by1: 12, by2: 24 },
  144: { bx: 34, ty1: 24, ty2: 120, ax: 108, ay: 72, bl: 68, br: 108, by1: 18, by2: 36 },
  192: { bx: 45, ty1: 32, ty2: 160, ax: 144, ay: 96, bl: 91, br: 144, by1: 24, by2: 48 },
};

/* ── A. 交付 PNG：一列里的竖直纯黑像素 ── */
const KL = { norm: 73150, wnorm: 765 };
function decompose(px, n) {
  const W = new Float64Array(n), R = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const r = px[i * 4], g = px[i * 4 + 1], b = px[i * 4 + 2];
    const wl = (r + g + b) / KL.wnorm, rw = Math.max(Math.abs(255 * wl - r), Math.abs(255 * wl - g), Math.abs(255 * wl - b));
    const rl = (255 * r + 69 * g + 58 * b) / KL.norm, rr = Math.max(Math.abs(255 * rl - r), Math.abs(69 * rl - g), Math.abs(58 * rl - b));
    if (rw <= rr) W[i] = wl; else R[i] = rl;
  }
  return { W, R };
}
function colProfile(P) {
  const im = decodePNG(readFileSync(join(PNGDIR, `ic_launcher_${P}.png`)));
  const px = toRGBA(im), n = P * P, { W, R } = decompose(px, n);
  const cols = [];
  for (let x = 0; x < P; x++) {
    let bBot = -1, tTop = P;
    for (let y = 0; y < P; y++) {
      if (R[y * P + x] > 0.004 && y > bBot) bBot = y;
      if (W[y * P + x] > 0.004 && y < tTop) tTop = y;
    }
    if (bBot < 0 || tTop >= P || tTop <= bBot) continue;
    let black = 0, dirty = 0, twoInk = 0; const samples = [];
    for (let y = bBot + 1; y < tTop; y++) {
      const i = (y * P + x) * 4, r = px[i], g = px[i + 1], b = px[i + 2];
      if (r === 0 && g === 0 && b === 0) black++;
      else { dirty++; if (W[y * P + x] > 0.02 && R[y * P + x] > 0.02) twoInk++; samples.push({ x, y, rgb: [r, g, b], w: +W[y * P + x].toFixed(3), rd: +R[y * P + x].toFixed(3) }); }
    }
    cols.push({ x, black, dirty, twoInk, samples });
  }
  const min = cols.reduce((a, c) => (c.black < a.black ? c : a), cols[0]);
  return { cols, narrowest: min, minBlack: min.black,
    atX: cols.filter(c => c.black === min.black).map(c => c.x),
    profile: cols.map(c => `${c.x}:${c.black}`).join(' '),
    dirtyTotal: cols.reduce((s, c) => s + c.dirty, 0),
    dirtySamples: cols.flatMap(c => c.samples),
    twoInkTotal: cols.reduce((s, c) => s + c.twoInk, 0) };
}

/* ── B. 解析：名义（bbox 角点）/ 实体（圆心−r）/ 竖直净空 ── */
function analytic(P) {
  const s = SNAP[P], r = (s.by2 - s.by1) / 2, cy = (s.by1 + s.by2) / 2;
  const dx = s.ax - s.bx, dy = s.ay - s.ty1, L = Math.hypot(dx, dy);
  const distTo = (x, y) => Math.abs(dx * (y - s.ty1) - dy * (x - s.bx)) / L;   // 斜边：过 (bx,ty1)，方向 (dx,dy)
  const nominal = distTo(s.bl, s.by2);              // bbox 左下角（被圆帽切掉的虚点）
  const capCx = s.bl + r;                           // 左端半圆圆心
  const solid = distTo(capCx, cy) - r;
  const vert = nominal * Math.sqrt(1 + (dy / dx) ** 2); // 同一 x 上的竖直净空
  return { r, nominal: +nominal.toFixed(4), solid: +solid.toFixed(4), vert: +vert.toFixed(3),
    k: +(solid / nominal).toFixed(3), tilt: +(Math.atan2(dy, dx) * 180 / Math.PI).toFixed(2) };
}

/* ── C. 10× 几何光栅 + 距离变换：不看任何 PNG ── */
function inTri(x, y, s) {
  const a = [s.bx, s.ty1], b = [s.bx, s.ty2], c = [s.ax, s.ay];
  const sg = (p, q, t) => (p[0] - t[0]) * (q[1] - t[1]) - (q[0] - t[0]) * (p[1] - t[1]);
  const d1 = sg([x, y], a, b), d2 = sg([x, y], b, c), d3 = sg([x, y], c, a);
  const neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
  return !(neg && pos);
}
function inPill(x, y, s) {
  const r = (s.by2 - s.by1) / 2, cy = (s.by1 + s.by2) / 2;
  if (y < s.by1 || y > s.by2) return false;
  if (x >= s.bl + r && x <= s.br - r) return true;
  const c = x < s.bl + r ? s.bl + r : s.br - r;
  return Math.hypot(x - c, y - cy) <= r;
}
function raster10(P) {
  const S = 10, N = P * S, s = SNAP[P];
  const tri = new Uint8Array(N * N), pil = new Uint8Array(N * N);
  let triN = 0, pilN = 0;
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    const fx = (x + 0.5) / S, fy = (y + 0.5) / S;
    if (inTri(fx, fy, s)) { tri[y * N + x] = 1; triN++; }
    if (inPill(fx, fy, s)) { pil[y * N + x] = 1; pilN++; }
  }
  const INF = 1e9, dist = new Float64Array(N * N).fill(INF), q = [];
  for (let p = 0; p < N * N; p++) if (tri[p]) { dist[p] = 0; q.push(p); }
  let head = 0;
  const NB = [[-1, 0, 1], [1, 0, 1], [0, -1, 1], [0, 1, 1], [-1, -1, 1.41421], [1, -1, 1.41421], [-1, 1, 1.41421], [1, 1, 1.41421]];
  while (head < q.length) {
    const p = q[head++], d = dist[p], x = p % N, y = (p / N) | 0;
    for (const [ox, oy, w] of NB) {
      const nx = x + ox, ny = y + oy;
      if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue;
      const t = ny * N + nx, nd = d + w;
      if (nd < dist[t]) { dist[t] = nd; q.push(t); }
    }
  }
  let best = INF, at = null;
  for (let p = 0; p < N * N; p++) if (pil[p] && dist[p] < best) { best = dist[p]; at = [p % N, (p / N) | 0]; }
  return { px: +(best / S).toFixed(3), at: [at[0] / S, at[1] / S].map(v => +v.toFixed(2)), triArea: triN / (S * S), pilArea: pilN / (S * S) };
}

/* ── 输出 ── */
const rows = [];
for (const P of SIZES) {
  const a = analytic(P), c = colProfile(P), m = raster10(P);
  rows.push({ P, nominal: a.nominal, solid: a.solid, k: a.k, vert: a.vert, tilt: a.tilt,
    raster10Solid: m.px, raster10At: m.at, delta: +(m.px - a.solid).toFixed(3),
    minPureBlack: c.minBlack, atX: c.atX.join(','), seamDirty: c.dirtyTotal, twoInk: c.twoInkTotal,
    dirtySamples: c.dirtySamples.slice(0, 4),
    rasterHeadroom: c.minBlack - 3, aaEaten: +(a.vert - c.minBlack).toFixed(2),
    profile48: P === 48 ? c.profile : undefined });
}
console.log(JSON.stringify({ rows, note: 'aaEaten = 竖直净空 − 纯黑像素数（抗锯齿吃掉的像素）' }, null, 1));
