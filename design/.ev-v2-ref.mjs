// Evidence Collector · 参考图复测（只读）：底板明度、四色实际值
// 目的：07 与 08 对「图标底」给的是两个相反的值（#F7F4EE L*96.27 vs #231C16 L*10.89），
// 用户给的参考图是唯一可判的第三方证据。
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG, toRGBA } from './.ev-png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REF = join(dirname(HERE), 'design/brand/ref');
const load = f => { const im = decodePNG(readFileSync(join(REF, f))); return { w: im.w, h: im.h, px: toRGBA(im) }; };
const hex = (r, g, b) => '#' + [r, g, b].map(v => v.toString(16).padStart(2, '0').toUpperCase()).join('');
const lin = c => { c /= 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const Y = (r, g, b) => 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
const Lstar = y => { const t = y > 216 / 24389 ? Math.cbrt(y) : (24389 / 27 * y + 16) / 116; return 116 * t - 16; };
const rel = (a, b) => { const [hi, lo] = a > b ? [a, b] : [b, a]; return (hi + 0.05) / (lo + 0.05); };

/* ── ref-netflix-icon.png：底板 vs 四周 ── */
const A = load('ref-netflix-icon.png');
const at = (im, x, y) => { const i = (y * im.w + x) * 4; return [im.px[i], im.px[i + 1], im.px[i + 2]]; };
// 中心 30×20 的中位数（避开字与圆角）
const med = (im, x0, y0, w, h) => {
  const ch = [[], [], []];
  for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) { const p = at(im, x, y); for (let k = 0; k < 3; k++) ch[k].push(p[k]); }
  return ch.map(a => { a.sort((p, q) => p - q); return a[a.length >> 1]; });
};
const plate = med(A, Math.round(A.w / 2) - 15, 8, 30, 20);          // 板内上部（字标在中间，取上缘内侧）
const corner = med(A, 2, 2, 6, 6);                                   // 左上角（板外）
const corner2 = med(A, A.w - 8, A.h - 8, 6, 6);                      // 右下角（板外）
// 亮度 50% 阈值下的板 bbox
let ymin = 255, ymax = 0, xmin = 255, xmax = 0;
const thr = (Math.max(...plate.map(lin)) + Math.max(...corner.map(lin))) / 2;
for (let y = 0; y < A.h; y++) for (let x = 0; x < A.w; x++) {
  const p = at(A, x, y);
  if (Y(...p) > thr) { if (y < ymin) ymin = y; if (y > ymax) ymax = y; if (x < xmin) xmin = x; if (x > xmax) xmax = x; }
}
// 板的面积占比（亮度阈值内的像素数 / 全图）
let plateArea = 0;
for (let y = 0; y < A.h; y++) for (let x = 0; x < A.w; x++) if (Y(...at(A, x, y)) > thr) plateArea++;

/* ── ref-wordmark.png：Live 与 Hub 的实际色 ── */
const B = load('ref-wordmark.png');
// 取每个字内部最饱和/最暗的众数
const mode = (im, x0, x1) => {
  const map = new Map();
  for (let y = 0; y < im.h; y++) for (let x = x0; x < x1; x++) { const k = at(im, x, y).join(','); map.set(k, (map.get(k) || 0) + 1); }
  return [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, 4).map(([k, n]) => ({ rgb: k, n }));
};
const left = mode(B, 0, Math.round(B.w * 0.48));
const right = mode(B, Math.round(B.w * 0.52), B.w);
// 极值：左半最亮（字标 Live）、右半最红（字标 Hub）
const extreme = (im, x0, x1) => {
  let bestL = null, bestS = null;
  for (let y = 0; y < im.h; y++) for (let x = x0; x < x1; x++) {
    const [r, g, b] = at(im, x, y);
    const y2 = Y(r, g, b), sat = Math.max(r, g, b) - Math.min(r, g, b);
    if (!bestL || y2 > bestL.y) bestL = { rgb: hex(r, g, b), y: +y2.toFixed(4), at: [x, y] };
    if (!bestS || sat > bestS.sat) bestS = { rgb: hex(r, g, b), sat, at: [x, y] };
  }
  return { brightest: bestL, mostSaturated: bestS };
};
// 红墨面积与纯红众数
let redPx = 0, redMode = new Map();
for (let y = 0; y < B.h; y++) for (let x = 0; x < B.w; x++) {
  const [r, g, b] = at(B, x, y);
  if (r > 120 && r - g > 60 && r - b > 60) { redPx++; const k = `${r},${g},${b}`; redMode.set(k, (redMode.get(k) || 0) + 1); }
}
const redTop = [...redMode.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3).map(([k, n]) => `${hex(...k.split(',').map(Number))} ×${n}`);

/* ── 输出 ── */
const p = { plate: hex(...plate), plateY: +Y(...plate).toFixed(4), plateL: +Lstar(Y(...plate)).toFixed(2),
  corner: hex(...corner), cornerY: +Y(...corner).toFixed(4), cornerL: +Lstar(Y(...corner)).toFixed(2),
  corner2: hex(...corner2),
  plateContrastOverSurround: +rel(Y(...plate), Y(...corner)).toFixed(3),
  plateBox: { x0: xmin, y0: ymin, x1: xmax, y1: ymax, w: xmax - xmin + 1, h: ymax - ymin + 1,
    ratio: +((xmax - xmin + 1) / (ymax - ymin + 1)).toFixed(4) },
  plateAreaPct: +(100 * plateArea / (A.w * A.h)).toFixed(1),
  canvas: [A.w, A.h] };
const w = { leftTop: left, rightTop: right, extremes: { left: extreme(B, 0, Math.round(B.w * 0.48)), right: extreme(B, Math.round(B.w * 0.52), B.w) },
  redPixels: redPx, redTop, canvas: [B.w, B.h] };

console.log(JSON.stringify({ icon: p, wordmark: w }, null, 1));
console.log('\n板明度结论：', p.plateL > 50 ? '浅底（07 的 #F7F4EE 方向）' : '深底（08 的 #231C16 方向）');
console.log('字标左半最亮：', w.extremes.left.brightest.rgb, ' 右半最红：', w.extremes.right.mostSaturated.rgb, ' 红墨众数：', redTop.join(' / '));
console.log('字标底色众数：', left[0].rgb, '→', hex(...left[0].rgb.split(',').map(Number)));
