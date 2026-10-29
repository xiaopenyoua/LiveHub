// v2 唯一的几何来源。所有脚本（build / verify / preview / splash）都从这里取数，
// 任何一处写死的尺寸都算 bug。
//
// 数字出处：
//   色值   ← 主理人终版四色（覆盖 08 之前的暖白方案）
//   比例   ← 参考图实测（ref-netflix-icon.png：r=0.125H，字宽 0.745W，cap 0.312H）
//   字体   ← design/.v2-wordmark.json（轮廓）+ design/.v2-wordmark.kern.json（带 kerning 的笔位）
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

export const WM = JSON.parse(readFileSync(join(HERE, '.v2-wordmark.json'), 'utf8'));
export const KERN = JSON.parse(readFileSync(join(HERE, '.v2-wordmark.kern.json'), 'utf8'));

export const PALETTE = {
  icon: '#231C16',     // 图标底（= 系统开屏 windowBackground，见 08 §4）
  splash: '#1A140F',   // 开屏底 / 应用内底
  live: '#FFFDFA',     // 字标 Live
  hub: '#FF453A',      // 字标 Hub（项目唯一的红）
};

export const CAP_EM = WM.capHeight / WM.upm;                    // 0.7275390625
export const INK_EM = KERN.inkWidth / WM.upm;                   // 3.732655（含 kerning，墨迹）
export const BOX_EM = KERN.boxWidth / WM.upm;                   // 3.828945（含 kerning，排版盒）
export const INK_X0 = KERN.inkX0;                               // 墨迹左缘相对 0 笔位（units）
export const BOUNDARY_U = KERN.boundaryFromInkLeft;             // Live/Hub 分界相对墨迹左缘

/** 三套几何。anchor 指「这一档以哪个量为设计锚点」，另一个量是派生结果。
 *  banner / splash 由主理人按 cap 比例给出；square 是按字标宽 0.78W 给出的（方形只受宽约束）。 */
const SPEC = [
  {
    key: 'banner', label: 'TV 桌面 banner 16:9', W: 320, H: 180, r: 22.5,
    anchor: { cap: 50.4 },                       // 0.28 × 180
  },
  {
    key: 'square', label: '方形 launcher', W: 512, H: 512, r: 64,
    anchor: { inkW: 399.36 },                    // 0.78 × 512
  },
  {
    key: 'splash', label: '开屏卡片（图标的 4.5 倍）', W: 1440, H: 810, r: 101.25,
    anchor: { cap: 226.8 },                      // 50.4 × 4.5 = 0.28 × 810
  },
];

export const GEOMS = SPEC.map(g => {
  const s = g.anchor.cap ? g.anchor.cap / CAP_EM : g.anchor.inkW / INK_EM;  // s = 字号(px)
  const F = s, scale = F / WM.upm;
  const cap = F * CAP_EM;
  const inkW = F * INK_EM;
  const boxW = F * BOX_EM;
  const inkLeft = (g.W - inkW) / 2;                 // 墨迹居中（不是排版盒居中，见 06 报告）
  const inkRight = g.W - inkLeft;
  const capTop = (g.H - cap) / 2;
  const baseline = capTop + cap;
  // 组变换：把 units 坐标搬到画布 —— 让墨迹左缘正好落在 inkLeft
  const tx = inkLeft - INK_X0 * scale;
  const boundaryX = inkLeft + BOUNDARY_U * scale;         // 笔位（排版意义上的分界）
  // 红墨首列 = H 的笔位 + H 自己的左边距；参考图的「分界 50.0%」量的是这个
  const redX0 = inkLeft + (KERN.penKern[4] + WM.letters[4].inkRel[0] - INK_X0) * scale;
  return {
    ...g, F, scale, cap, inkW, boxW, inkLeft, inkRight, capTop, baseline, tx,
    boundaryX, redX0,
    margins: inkLeft,
    pctW: inkW / g.W, pctCap: cap / g.H,
    // 主理人表里的值，只为对照
    spec: g.key === 'banner' ? { inkW: 266.7, F: 69.3, margins: 26.7 }
      : g.key === 'square' ? { inkW: 399.4, F: 103.8, cap: 75.5 }
        : { inkW: 1200.2, F: 311.85, margins: 120 },
  };
});

export const byKey = k => GEOMS.find(g => g.key === k);

/* ---------------- SVG 片段 ---------------- */

/** 字标：7 个 <path>，每段 d 逐字节相同（只有外层 scale 不同）→「同一张图放大」是可验的 */
export function wordmarkGroup(g, { fill = null, ids = false } = {}) {
  const live = WM.letters.slice(0, 4), hub = WM.letters.slice(4);
  const one = (l, i) => `    <path${ids ? ` id="${g.key}-${i}"` : ''} transform="translate(${fmt(KERN.penKern[i], 3)} 0)" d="${l.d}"/>`;
  const livePaths = live.map((l, i) => one(l, i)).join('\n');
  const hubPaths = hub.map((l, i) => one(l, i + 4)).join('\n');
  const paint = (n, d) => fill ? `fill="${fill}"` : `fill="${n}"`;
  return `  <g transform="translate(${fmt(g.tx, 3)} ${fmt(g.baseline, 3)}) scale(${fmt(g.scale, 8)})">\n`
    + `  <g ${paint(PALETTE.live, 'live')}>\n${livePaths}\n  </g>\n`
    + `  <g ${paint(PALETTE.hub, 'hub')}>\n${hubPaths}\n  </g>\n`
    + `  </g>`;
}

export const fmt = (v, n = 3) => {
  const r = Number(v.toFixed(n));
  return Object.is(r, -0) ? '0' : String(r);
};

/** 完整图标 SVG：底板（满出血 / 圆角透明）+ 字标。out 只改位图尺寸，viewBox 恒为设计尺寸 */
export function iconSvg(g, { plate = true, bleed = false, out = null } = {}) {
  const W = out ? out.w : g.W, H = out ? out.h : g.H;
  const head = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${g.W} ${g.H}" shape-rendering="geometricPrecision">`;
  const body = [];
  if (bleed || !plate) body.push(`  <rect width="${g.W}" height="${g.H}" fill="${PALETTE.icon}"/>`);
  else body.push(`  <rect x="0" y="0" width="${g.W}" height="${g.H}" rx="${g.r}" ry="${g.r}" fill="${PALETTE.icon}"/>`);
  body.push(wordmarkGroup(g));
  return `${head}\n${body.join('\n')}\n</svg>\n`;
}

/* ---------------- 与主理人表格的对照断言 ---------------- */
export function assertAgainstSpec() {
  const rows = [];
  for (const g of GEOMS) {
    const d = [];
    if (g.spec.inkW && Math.abs(g.inkW - g.spec.inkW) / g.spec.inkW > 0.02) {
      d.push(`墨宽 ${g.inkW.toFixed(2)} vs 表 ${g.spec.inkW}（${(100 * (g.inkW / g.spec.inkW - 1)).toFixed(2)}%）`);
    }
    if (g.spec.F && Math.abs(g.F - g.spec.F) / g.spec.F > 0.02) d.push(`字号 ${g.F.toFixed(2)} vs 表 ${g.spec.F}`);
    if (g.spec.cap && Math.abs(g.cap - g.spec.cap) / g.spec.cap > 0.02) d.push(`cap ${g.cap.toFixed(2)} vs 表 ${g.spec.cap}`);
    rows.push({ key: g.key, drift: d });
  }
  return rows;
}
