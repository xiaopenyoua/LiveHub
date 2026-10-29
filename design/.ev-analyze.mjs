// Evidence Collector · 独立像素分析
// 混合模型（不猜阈值，先定模型再算）：
//   交付 PNG 每像素 = w·#FFFFFF + rd·#FF453A + (1−w−rd)·#000000，sRGB 8bit 直混。
//   r=255(w+rd)、g=255w+69rd、b=255w+58rd  ⇒  rd=(g−b)/11，w=r/255−rd（精确解，无阈值）。
//   残差 >2 的像素 = 落在两条混合线之外 = 第四色/多墨混合。
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG, toRGBA } from './.ev-png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const PNGDIR = join(ROOT, 'assets/brand/icon/png');
const RES = join(ROOT, 'assets/brand/android-res');
const TMP = join(ROOT, 'design/brand/shots/evidence/_ev');
const SIZES = [48, 72, 96, 144, 192];

const load = f => { const im = decodePNG(readFileSync(f)); return { w: im.w, h: im.h, ct: im.ct, bd: im.bd, hasTRNS: im.hasTRNS, px: toRGBA(im) }; };

/** 每像素解出 w / rd / 残差
 *  先判「落在哪条混合线上」，再解权重。用最小二乘按通道比例解，避免 (g-b)/11 的整数量化放大
 *  （g、b 各带 ±0.5 舍入时 (g-b)/11 的误差可达 0.09，乘 186 就是 17 个单位的假残差）。 */
const KL = { wr: 255, wg: 255, wb: 255, rr: 255, rg: 69, rb: 58, norm: 73150, wnorm: 765 };
function decompose(img) {
  const { w, h, px } = img, n = w * h;
  const W = new Float64Array(n), R = new Float64Array(n), RES = new Int32Array(n);
  let third = 0, whiteRedMix = 0, maxRes = 0;
  const maxResAt = [];
  for (let i = 0; i < n; i++) {
    const r = px[i * 4], g = px[i * 4 + 1], b = px[i * 4 + 2], a = px[i * 4 + 3];
    if (a !== 255) throw new Error('alpha != 255 @' + i);
    // 白线：r=g=b=255·w
    const wl = (r + g + b) / KL.wnorm;
    const rw = Math.max(Math.abs(255 * wl - r), Math.abs(255 * wl - g), Math.abs(255 * wl - b));
    // 红线：c = rd·(255,69,58)
    const rl = (255 * r + 69 * g + 58 * b) / KL.norm;
    const rr = Math.max(Math.abs(255 * rl - r), Math.abs(69 * rl - g), Math.abs(58 * rl - b));
    let ww = 0, rd = 0, res;
    if (rw <= rr) { ww = wl; res = rw; } else { rd = rl; res = rr; }
    // 双墨混合检验：同时解得 w 与 rd，若两者都显著且拟合反而更好 → 灰桥/第四色
    const det = 255 * 255 * 2 + 69 * 69 + 58 * 58; // 简化：用联合最小二乘
    {
      // 解 [255 255; 255 69; 255 58] · [w, rd] = [r,g,b] 的正规方程
      const a11 = 3 * 255 * 255, a12 = 255 * 255 + 255 * 69 + 255 * 58, a22 = 255 * 255 + 69 * 69 + 58 * 58;
      const b1 = 255 * (r + g + b), b2 = 255 * r + 69 * g + 58 * b;
      const dd = a11 * a22 - a12 * a12;
      const w2 = (b1 * a22 - b2 * a12) / dd, r2 = (a11 * b2 - a12 * b1) / dd;
      if (w2 > 0.03 && r2 > 0.03 && w2 + r2 < 1.02) {
        const e = Math.max(Math.abs(255 * (w2 + r2) - r), Math.abs(255 * w2 + 69 * r2 - g), Math.abs(255 * w2 + 58 * r2 - b));
        if (e < res) { whiteRedMix++; ww = w2; rd = r2; res = e; }
      }
      void det;
    }
    W[i] = ww; R[i] = rd; RES[i] = Math.round(res);
    if (res > maxRes) maxRes = res;
    if (res > 2.0) { third++; if (maxResAt.length < 6) maxResAt.push({ x: i % w, y: (i / w) | 0, rgb: [r, g, b], res: +res.toFixed(2) }); }
  }
  return { W, R, RES, third, whiteRedMix, maxRes: +maxRes.toFixed(2), maxResAt };
}

/** 逐像素比对：返回差异像素数 / 最大通道差 / 平均差 */
function diff(A, B) {
  if (A.w !== B.w) throw new Error('尺寸不同');
  const n = A.w * A.h; let cnt = 0, maxd = 0, sum = 0; const spots = [];
  for (let i = 0; i < n; i++) {
    let d = 0;
    for (let c = 0; c < 3; c++) d = Math.max(d, Math.abs(A.px[i * 4 + c] - B.px[i * 4 + c]));
    if (d > 0) { cnt++; sum += d; if (d > maxd) maxd = d; if (spots.length < 5) spots.push({ x: i % A.w, y: (i / A.w) | 0, d }); }
  }
  return { cnt, pct: +(100 * cnt / n).toFixed(2), maxd, mean: cnt ? +(sum / cnt).toFixed(1) : 0, spots };
}

/** box（面积平均）缩放，用于模拟「位图缩放」这一假设 */
function boxDown(img, P) {
  const f = img.w / P;
  if (!Number.isInteger(f)) return null;
  const o = { w: P, h: P, px: Buffer.alloc(P * P * 4) };
  for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) {
    let s = [0, 0, 0];
    for (let dy = 0; dy < f; dy++) for (let dx = 0; dx < f; dx++) {
      const i = ((y * f + dy) * img.w + (x * f + dx)) * 4;
      s[0] += img.px[i]; s[1] += img.px[i + 1]; s[2] += img.px[i + 2];
    }
    const o0 = (y * P + x) * 4;
    o.px[o0] = Math.round(s[0] / (f * f)); o.px[o0 + 1] = Math.round(s[1] / (f * f)); o.px[o0 + 2] = Math.round(s[2] / (f * f)); o.px[o0 + 3] = 255;
  }
  return o;
}

const out = {};

/* ═══ 1. PNG 是不是原生矢量光栅 ═══ */
const rasters = {
  svg: Object.fromEntries(SIZES.map(P => [P, load(join(TMP, `svg-${P}.png`))])),
  snap: Object.fromEntries(SIZES.map(P => [P, load(join(TMP, `snap-${P}.png`))])),
};
const delivered = Object.fromEntries(SIZES.map(P => [P, load(join(PNGDIR, `ic_launcher_${P}.png`))]));
out.check1 = {};
for (const P of SIZES) {
  out.check1[P] = {
    vsDeliveredSvgExact: diff(delivered[P], rasters.svg[P]),
    vsSpecIntegerSnap: diff(delivered[P], rasters.snap[P]),
    fileHeader: { ct: delivered[P].ct, bd: delivered[P].bd, hasTRNS: delivered[P].hasTRNS, bytes: readFileSync(join(PNGDIR, `ic_launcher_${P}.png`)).length },
  };
}
// 位图缩放假设：拿更大档缩到小档，看能不能比原生光栅更接近
out.check1.downscaleHypothesis = {};
for (const [from, to] of [[192, 48], [192, 96], [96, 48], [144, 48], [192, 72], [192, 144]]) {
  const d = boxDown(delivered[from], to);
  if (d) out.check1.downscaleHypothesis[`${from}->${to}`] = diff(delivered[to], d);
}

/* ═══ 2/3/4/5/6. 五档逐像素体检 ═══ */
out.checks = {};
for (const P of SIZES) {
  const img = delivered[P], dec = decompose(img), n = P * P;
  let pureB = 0, pureW = 0, pureR = 0, mid = 0;
  for (let i = 0; i < n; i++) {
    const r = img.px[i * 4], g = img.px[i * 4 + 1], b = img.px[i * 4 + 2];
    if (r === 0 && g === 0 && b === 0) pureB++;
    else if (r === 255 && g === 255 && b === 255) pureW++;
    else if (r === 255 && g === 69 && b === 58) pureR++;
    else mid++;
  }
  // 红墨量
  let redArea = 0, whiteArea = 0;
  for (let i = 0; i < n; i++) { redArea += Math.max(0, Math.min(1, dec.R[i])); whiteArea += Math.max(0, dec.W[i]); }

  // 4. 右缘对齐（两套阈值：任意覆盖 / ≥50% 实体）
  const colAny = t => { for (let x = P - 1; x >= 0; x--) { for (let y = 0; y < P; y++) if (t(y * P + x) > 0.004) return x; } return -1; };
  const colSolid = t => { for (let x = P - 1; x >= 0; x--) { for (let y = 0; y < P; y++) if (t(y * P + x) >= 0.5) return x; } return -1; };
  const rAny = colAny(i => dec.R[i]), rSol = colSolid(i => dec.R[i]);
  const wAny = colAny(i => dec.W[i]), wSol = colSolid(i => dec.W[i]);
  const colCov = (t, x) => { let c = 0; for (let y = 0; y < P; y++) c += Math.min(1, Math.max(0, t(y * P + x))); return +c.toFixed(2); };
  const colPeak = (t, x) => { let c = 0; for (let y = 0; y < P; y++) c = Math.max(c, Math.min(1, Math.max(0, t(y * P + x)))); return +c.toFixed(3); };

  // 6. 徽章 bbox / 宽高比
  const bbox = th => {
    let x0 = P, x1 = -1, y0 = P, y1 = -1;
    for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) if (dec.R[y * P + x] > th) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    return { x0, x1, y0, y1, w: x1 - x0 + 1, h: y1 - y0 + 1, ratio: +((x1 - x0 + 1) / (y1 - y0 + 1)).toFixed(3) };
  };
  const bSolid = bbox(0.5), bAny = bbox(0.004);

  // 三角 bbox（白墨水）
  const tbox = th => {
    let x0 = P, x1 = -1, y0 = P, y1 = -1;
    for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) if (dec.W[y * P + x] > th) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    return { x0, x1, y0, y1, w: x1 - x0 + 1, h: y1 - y0 + 1 };
  };

  out.checks[P] = {
    colors: { pureBlack: pureB, pureWhite: pureW, pureRed: pureR, mid, midPct: +(100 * mid / n).toFixed(2),
      thirdColorPixels: dec.third, whiteRedMixPixels: dec.whiteRedMix, maxResidual: dec.maxRes, residualSamples: dec.maxResAt },
    redInk: { area: +redArea.toFixed(2), pctOfCanvas: +(100 * redArea / n).toFixed(3), pctOf108Cube: +(100 * redArea / n * (72 / 108) ** 2).toFixed(3),
      whiteArea: +whiteArea.toFixed(2), whiteToRed: +(whiteArea / redArea).toFixed(2) },
    rightEdge: {
      badgeAny: rAny, badgeSolid: rSol, triAny: wAny, triSolid: wSol,
      alignedAny: rAny === wAny, alignedSolid: rSol === wSol,
      badgeCovAtLast: +colCov(i => dec.R[i], rAny).toFixed(3), triCovAtLast: +colCov(i => dec.W[i], wAny).toFixed(3),
      badgePeakAtLast: colPeak(i => dec.R[i], rAny), triPeakAtLast: colPeak(i => dec.W[i], wAny),
      badgeCovAtSolid: +colCov(i => dec.R[i], rSol).toFixed(3), triCovAtSolid: +colCov(i => dec.W[i], wSol).toFixed(3),
    },
    badgeBox: { solid50: bSolid, anyCoverage: bAny },
    triBox: { solid50: tbox(0.5), anyCoverage: tbox(0.004) },
  };
}

/* ═══ 3. 48 档黑缝：逐列数纯黑行 ═══ */
function seamProfile(P) {
  const img = delivered[P], dec = decompose(img);
  const run = (x, thR, thW) => {
    let bBot = -1, tTop = P;
    for (let y = 0; y < P; y++) { if (dec.R[y * P + x] > thR && y > bBot) bBot = y; if (dec.W[y * P + x] > thW && y < tTop) tTop = y; }
    if (bBot < 0 || tTop >= P || tTop <= bBot) return null;
    let black = 0; const dirty = [];
    for (let y = bBot + 1; y < tTop; y++) {
      const i = (y * P + x) * 4, r = img.px[i], g = img.px[i + 1], b = img.px[i + 2];
      if (r === 0 && g === 0 && b === 0) black++;
      else dirty.push({ x, y, rgb: [r, g, b], w: +dec.W[y * P + x].toFixed(2), rd: +dec.R[y * P + x].toFixed(2) });
    }
    return { x, bBot, tTop, black, dirty };
  };
  const strict = [], solid = [];
  for (let x = 0; x < P; x++) { const a = run(x, 0.004, 0.004); if (a) strict.push(a); const b = run(x, 0.5, 0.5); if (b) solid.push(b); }
  const blackStrict = strict.filter(r => r.black !== null).map(r => r.black);
  return { P,
    inkToInk: { minBlack: blackStrict.length ? Math.min(...blackStrict) : null, at: strict.filter(r => r.black === Math.min(...blackStrict)).map(r => r.x),
      profile: strict.map(r => `${r.x}:${r.black}`).join(' ') },
    solidToSolid: { minBlack: Math.min(...solid.map(r => r.black)), profile: solid.map(r => `${r.x}:${r.black}`).join(' ') },
    // 「灰桥」的严格定义：缝内同时含白与红的像素（双墨混合）
    bridgesStrict: strict.flatMap(r => r.dirty).filter(d => d.w > 0.02 && d.rd > 0.02),
    nonBlackInSeam: strict.flatMap(r => r.dirty).length,
    nonBlackSamples: strict.flatMap(r => r.dirty).slice(0, 6),
  };
}
/** 墨水连通域：两块是否被黑缝真正分开（不依赖任何 alpha/阈值叙事） */
function inkComponents(P, th) {
  const img = delivered[P], dec = decompose(img), N = P * P;
  const ink = new Uint8Array(N);
  for (let i = 0; i < N; i++) ink[i] = (dec.W[i] > th || dec.R[i] > th) ? 1 : 0;
  const lab = new Int32Array(N).fill(-1); const comps = [];
  for (let s = 0; s < N; s++) {
    if (!ink[s] || lab[s] >= 0) continue;
    const idc = comps.length; let area = 0, whitePx = 0, redPx = 0; const st = [s]; lab[s] = idc;
    while (st.length) {
      const p = st.pop(); area++;
      if (dec.R[p] >= dec.W[p]) redPx++; else whitePx++;
      const x = p % P;
      for (const q of [p - 1, p + 1, p - P, p + P]) {
        if (q < 0 || q >= N) continue;
        if ((q === p - 1 && x === 0) || (q === p + 1 && x === P - 1)) continue;
        if (ink[q] && lab[q] < 0) { lab[q] = idc; st.push(q); }
      }
    }
    comps.push({ area, whitePx, redPx });
  }
  return { threshold: th, components: comps.length, detail: comps };
}
out.check3 = {
  seam48: seamProfile(48),
  inkConnectivity: [inkComponents(48, 0.5), inkComponents(96, 0.5), inkComponents(192, 0.5), inkComponents(48, 0.02)],
  whiteRedMixWholeImage: decompose(delivered[48]).whiteRedMix,
};

/* ═══ 7. monochrome 层 ═══ */
{
  const a = {};   // alpha 由 g 通道还原（白墨铺纯红底：r 恒 255，g=b=255·α）
  for (const P of [48, 96, 192]) {
    const im = load(join(TMP, `mono-${P}.png`));
    const al = new Float64Array(P * P); let badR = 0;
    for (let i = 0; i < P * P; i++) { if (im.px[i * 4] !== 255) badR++; al[i] = im.px[i * 4 + 1] / 255; }
    // 缝带：两块 bbox 之间的区域（三角右上／徽章左下）
    let minGap = 1e9, zeroRun = 0;
    const mid = [];
    for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) {
      const v = al[y * P + x];
      if (v > 0 && v < 1) mid.push(v);
    }
    a[P] = {
      cornerAlpha: [al[0], al[P - 1], al[(P - 1) * P], al[P * P - 1]].map(v => +v.toFixed(3)),
      alphaZeroPct: +(100 * Array.from(al).filter(v => v === 0).length / (P * P)).toFixed(2),
      alphaFullPct: +(100 * Array.from(al).filter(v => v === 1).length / (P * P)).toFixed(2),
      badRedPixels: badR,
      // 是否整体一块矩形（底板）：四边中点 alpha
      edgeMidAlpha: [al[0 * P + (P >> 1)], al[(P - 1) * P + (P >> 1)], al[(P >> 1) * P + 0], al[(P >> 1) * P + P - 1]].map(v => +v.toFixed(3)),
    };
  }
  // 10× 全层：两块是否在拓扑上分离（连通域标记）
  const im = load(join(TMP, 'mono1080.png'));
  const W = 1080, H = 1080, al = new Float64Array(W * H);
  for (let i = 0; i < W * H; i++) al[i] = im.px[i * 4 + 1] / 255;
  const lab = new Int32Array(W * H).fill(-1);
  const comps = []; const stack = [];
  for (let s = 0; s < W * H; s++) {
    if (al[s] < 0.5 || lab[s] >= 0) continue;
    const idc = comps.length; let area = 0; let x0 = W, x1 = -1, y0 = H, y1 = -1;
    stack.length = 0; stack.push(s); lab[s] = idc;
    while (stack.length) {
      const p = stack.pop(); area++;
      const px = p % W, py = (p / W) | 0;
      if (px < x0) x0 = px; if (px > x1) x1 = px; if (py < y0) y0 = py; if (py > y1) y1 = py;
      const nb = [p - 1, p + 1, p - W, p + W];
      for (let k = 0; k < 4; k++) {
        const q = nb[k];
        if (q < 0 || q >= W * H) continue;
        if (k < 2 && ((k === 0 && px === 0) || (k === 1 && px === W - 1))) continue;
        if (al[q] >= 0.5 && lab[q] < 0) { lab[q] = idc; stack.push(q); }
      }
    }
    comps.push({ area, box: [x0, y0, x1, y1], w: x1 - x0 + 1, h: y1 - y0 + 1 });
  }
  // 缝：由徽章左下角向斜边做垂线，采样这条线段上的 alpha
  const bx = 35, by = 30, ax = 72, ay = 54;                 // 108 系斜边
  const dx = ax - bx, dy = ay - by, L = Math.hypot(dx, dy);
  const nx = dy / L, ny = -dx / L;                          // 法向（指向左上，即徽章侧）
  const sx = 52, sy = 36;                                   // 徽章左下角（约束点）
  const d0 = Math.abs(dx * (sy - by) - dy * (sx - bx)) / L; // = 4.2175
  // 缝的真实宽度：两块实体（alpha≥0.5）之间的最小欧氏距离（对「三角墨」做距离变换，取「徽章墨」上的最小值）
  const W2 = W, H2 = H;
  const INF = 1e9, dist = new Float64Array(W2 * H2).fill(INF);
  const q = []; let head = 0;
  // 以「面积最大 = 三角」为源；另一块为目标
  const triId = comps[0].area > comps[1].area ? 0 : 1, pillId = 1 - triId;
  for (let p = 0; p < W2 * H2; p++) if (lab[p] === triId) { dist[p] = 0; q.push(p); }
  while (head < q.length) {
    const p = q[head++]; const d = dist[p], x = p % W2, y = (p / W2) | 0;
    const nb = [[x - 1, y, 1], [x + 1, y, 1], [x, y - 1, 1], [x, y + 1, 1], [x - 1, y - 1, 1.41421], [x + 1, y - 1, 1.41421], [x - 1, y + 1, 1.41421], [x + 1, y + 1, 1.41421]];
    for (const [nx2, ny2, wgt] of nb) {
      if (nx2 < 0 || ny2 < 0 || nx2 >= W2 || ny2 >= H2) continue;
      const t2 = ny2 * W2 + nx2, nd = d + wgt;
      if (nd < dist[t2]) { dist[t2] = nd; q.push(t2); }
    }
  }
  let minSolidGapPx = INF, at = null;
  for (let p = 0; p < W2 * H2; p++) if (lab[p] === pillId && dist[p] < minSolidGapPx) { minSolidGapPx = dist[p]; at = [p % W2, (p / W2) | 0]; }
  // 沿「最近两点连线」采样 alpha，看缝里到底是 0 还是有半透明灰
  let bestT = null, bestD = INF;
  if (at) {
    const R2 = 90;
    for (let y = Math.max(0, at[1] - R2); y <= Math.min(H - 1, at[1] + R2); y++)
      for (let x = Math.max(0, at[0] - R2); x <= Math.min(W - 1, at[0] + R2); x++) {
        if (lab[y * W + x] !== triId) continue;
        const d = Math.hypot(x - at[0], y - at[1]);
        if (d < bestD) { bestD = d; bestT = [x, y]; }
      }
  }
  let lineMax = 0, lineZero = 0, lineN = 0, lineMin = 9;
  if (bestT) {
    const steps = 200;
    for (let s = 0; s <= steps; s++) {
      const t = s / steps;
      const x = Math.round(at[0] + (bestT[0] - at[0]) * t), y = Math.round(at[1] + (bestT[1] - at[1]) * t);
      const v = al[y * W + x];
      lineN++; lineMax = Math.max(lineMax, v); lineMin = Math.min(lineMin, v);
      if (v === 0) lineZero++;
    }
  }
  out.check7 = { perSize: a, components10x: comps, triId, pillId,
    minSolidGapUnits: +(bestD / 10).toFixed(3), nominalConstraintDist: +(Math.abs(dx * (36 - by) - dy * (52 - bx)) / L).toFixed(4),
    gapEndpoints108: at && bestT ? [[at[0] / 10, at[1] / 10], [bestT[0] / 10, bestT[1] / 10]] : null,
    seamLine: { samples: lineN, alphaZeroSamples: lineZero, alphaMax: +lineMax.toFixed(3), alphaMin: +lineMin.toFixed(3) },
    pathCountInFile: 0, };
  out.check7.pathCountInFile = (readFileSync(join(ROOT, 'assets/brand/icon/ic_launcher_monochrome.svg'), 'utf8').match(/<path/g) || []).length;
}

/* ═══ 8. 自适应图标完整性 + md5 ═══ */
{
  const { createHash } = await import('node:crypto');
  const md5 = f => createHash('md5').update(readFileSync(f)).digest('hex');
  const BUCKET = { 48: 'mdpi', 72: 'hdpi', 96: 'xhdpi', 144: 'xxhdpi', 192: 'xxxhdpi' };
  const v26 = readFileSync(join(RES, 'mipmap-anydpi-v26/ic_launcher.xml'), 'utf8');
  const v33 = readFileSync(join(RES, 'mipmap-anydpi-v33/ic_launcher.xml'), 'utf8');
  const tags = s => (s.match(/<(background|foreground|monochrome)\b/g) || []).map(t => t.slice(1));
  const colors = readFileSync(join(RES, 'values/ic_launcher_colors.xml'), 'utf8');
  const refs = [...new Set((v26 + v33).match(/@(color|drawable|mipmap)\/[A-Za-z0-9_]+/g) || [])];
  const resolves = refs.map(r => {
    const [kind, name] = r.slice(1).split('/');
    const ff = (kind === 'color') ? join(RES, 'values/ic_launcher_colors.xml') : join(RES, `drawable/${name}.xml`);
    let exists = false, decl = null;
    try {
      const txt = readFileSync(ff, 'utf8');
      exists = true;
      decl = kind === 'color' ? (txt.match(new RegExp(`<color name="${name}">([^<]+)</color>`)) || [])[1] : (txt.match(/<vector\b/) ? 'vector' : null);
    } catch { exists = false; }
    return { ref: r, file: ff.replace(RES + '/', ''), fileExists: exists, declared: decl };
  });
  out.check8 = {
    v26Children: tags(v26), v33Children: tags(v33),
    v26HasMonochrome: tags(v26).includes('monochrome'), v33HasMonochrome: tags(v33).includes('monochrome'),
    backgroundValue: (colors.match(/<color[^>]*>([^<]+)</) || [])[1],
    refs, resolves,
    md5Identical: Object.entries(BUCKET).map(([P, b]) => ({
      P, from: md5(join(PNGDIR, `ic_launcher_${P}.png`)), to: md5(join(RES, `mipmap-${b}/ic_launcher.png`)),
      same: md5(join(PNGDIR, `ic_launcher_${P}.png`)) === md5(join(RES, `mipmap-${b}/ic_launcher.png`)),
    })),
    files: (() => { const r = []; const walk = d => { for (const e of readdirSync(d, { withFileTypes: true })) { const p = join(d, e.name); e.isDirectory() ? walk(p) : r.push(p.replace(RES + '/', '')); } }; walk(RES); return r.sort(); })(),
  };
}

/* ═══ 9. 3 米可读性代理 ═══ */
{
  const MM_PER_PX = 1219 / 1920;               // 55″ 1080p：屏宽 1219mm / 1920px
  const DIST_MM = 3000;
  const arcmin = mm => (mm / DIST_MM) * (180 / Math.PI) * 60;
  const pxArcmin = arcmin(MM_PER_PX);
  const P = 48, c = out.checks[48];
  const badgeH = c.badgeBox.solid50.h, badgeW = c.badgeBox.solid50.w;
  const triH = c.triBox.solid50.h, triW = c.triBox.solid50.w;
  const seamPx = out.check3.seam48.inkToInk.minBlack;
  const seamPxSolid = out.check3.seam48.solidToSolid.minBlack;
  out.check9 = {
    mmPerPx: +MM_PER_PX.toFixed(4), pxArcmin: +pxArcmin.toFixed(3),
    iconMm: +(P * MM_PER_PX).toFixed(2), iconArcmin: +arcmin(P * MM_PER_PX).toFixed(2),
    badge: { px: [badgeW, badgeH], arcmin: [+(badgeW * pxArcmin).toFixed(2), +(badgeH * pxArcmin).toFixed(2)], features: +(badgeH * pxArcmin / 2).toFixed(2) },
    seam: { px: seamPx, pxSolid: seamPxSolid, arcmin: +(seamPx * pxArcmin).toFixed(2), features: +(seamPx * pxArcmin / 2).toFixed(2),
      arcminSolid: +(seamPxSolid * pxArcmin).toFixed(2), featuresSolid: +(seamPxSolid * pxArcmin / 2).toFixed(2) },
    tri: { px: [triW, triH], arcmin: [+(triW * pxArcmin).toFixed(2), +(triH * pxArcmin).toFixed(2)], features: +(triH * pxArcmin / 2).toFixed(2) },
  };
}

console.log(JSON.stringify(out, null, 1));
