// LiveHub 应用图标 · 单一几何源
// 所有资产（SVG / VectorDrawable / PNG）都从这里取数，禁止各文件自己写死一套。
// 数字来源：design/brand/02-图标几何规格.md §0 / §2（终值，不重算比例）。

export const PALETTE = { black: '#000000', white: '#FFFFFF', red: '#FF453A' };

// 108 图层系（VectorDrawable / 自适应图标前景层）
export const L108 = {
  tri:   { x: 35, y1: 30, y2: 78, apexX: 72, apexY: 54 },
  badge: { x1: 52, x2: 72, y1: 27, y2: 36, r: 4.5 },
};

// 72 视口系 = 108 系整体减 18（PNG 满出血 = 视口）
export const OFFSET = 18;

// 02 §2 表格终值，用来断言「规矩算出来的 == 规格写的」
export const EXPECTED = {
  48:  { tw: 25, th: 32, bx: 11, apexX: 36, apexY: 24, bw: 13, bh: 6,  bl: 23, br: 36,  gap: 3.10, gapMin: 2.56 },
  72:  { tw: 37, th: 48, bx: 17, apexX: 54, apexY: 36, bw: 20, bh: 9,  bl: 34, br: 54,  gap: 4.22, gapMin: 3.84 },
  96:  { tw: 49, th: 64, bx: 23, apexX: 72, apexY: 48, bw: 27, bh: 12, bl: 45, br: 72,  gap: 5.33, gapMin: 5.12 },
  144: { tw: 74, th: 96, bx: 34, apexX: 108, apexY: 72, bw: 40, bh: 18, bl: 68, br: 108, gap: 8.44, gapMin: 7.68 },
  192: { tw: 99, th: 128, bx: 45, apexX: 144, apexY: 96, bw: 53, bh: 24, bl: 91, br: 144, gap: 11.54, gapMin: 10.24 },
};

export const SIZES = [48, 72, 96, 144, 192];

/** 点到直线 (ax,ay)-(bx,by) 的垂距（法向距离） */
export function normDist(px, py, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay;
  return Math.abs(dx * (py - ay) - dy * (px - ax)) / Math.hypot(dx, dy);
}

/**
 * 五档整数几何。
 * 取整规则（02 §2，五档通用）：
 *  1) 顶点 x 先定，徽章右缘取同一整数（差 0）
 *  2) 所有 y 各自取最近整数；徽章高 = round(9k)
 *  3) 三角底边 x、徽章左缘取最近整数
 *  4) 闸门：缝 < 0.08×三角高 → 徽章左缘左移 1px（只此一次）
 */
export function geom(P) {
  const k = P / 72;
  const R = v => Math.round(v);

  const apexX = R(54 * k);
  const ty1 = R(12 * k);
  const ty2 = R(60 * k);
  const th = ty2 - ty1;
  const apexY = R(36 * k);

  const bh = R(9 * k);
  const by1 = ty1 - R(0.06 * th);
  const by2 = by1 + bh;

  const bx = R(17 * k);
  let bl = R(34 * k);
  const br = apexX;

  // 缝 = 徽章左下角到斜边的法向距离（斜边是斜的，量竖直距离是错的）
  let gap = normDist(bl, by2, bx, ty1, apexX, apexY);
  const gapMin = 0.08 * th;
  let gate = 0;
  if (gap < gapMin) { bl -= 1; gate = 1; gap = normDist(bl, by2, bx, ty1, apexX, apexY); }

  return {
    P, k,
    tri: { x: bx, y1: ty1, y2: ty2, apexX, apexY, w: apexX - bx, h: th },
    badge: { x1: bl, x2: br, y1: by1, y2: by2, w: br - bl, h: bh, r: bh / 2 },
    gap, gapMin, gate,
  };
}

const num = n => {
  const s = (+n).toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
  return s === '-0' ? '0' : s;
};

/**
 * 任意画布尺寸的「精确」几何：72 视口坐标 × k，**不取整**。
 * 只给矢量母版用（512 / 1024）。位图五档必须走 geom() 的取整规则，
 * 否则像素网格上会得到 120.8889 这种没法落在像素边界的坐标。
 */
export function geomExact(P) {
  const k = P / 72;
  const g = {
    P, k,
    tri: { x: 17 * k, y1: 12 * k, y2: 60 * k, apexX: 54 * k, apexY: 36 * k, w: 37 * k, h: 48 * k },
    badge: { x1: 34 * k, x2: 54 * k, y1: 9 * k, y2: 18 * k, w: 20 * k, h: 9 * k, r: 4.5 * k },
    gapMin: 3.84 * k, gate: 0,
  };
  g.gap = normDist(g.badge.x1, g.badge.y2, g.tri.x, g.tri.y1, g.tri.apexX, g.tri.apexY);
  return g;
}

export const triPath = (t) =>
  `M${num(t.x)},${num(t.y1)} L${num(t.x)},${num(t.y2)} L${num(t.apexX)},${num(t.apexY)} Z`;

/** 圆角条：四段 1/4 圆弧，r = 高/2（全圆） */
export const pillPath = (b) => {
  const r = (b.y2 - b.y1) / 2, cy = (b.y1 + b.y2) / 2;
  return [
    `M${num(b.x1)},${num(cy)}`,
    `A${num(r)},${num(r)} 0 0 1 ${num(b.x1 + r)},${num(b.y1)}`,
    `H${num(b.x2 - r)}`,
    `A${num(r)},${num(r)} 0 0 1 ${num(b.x2)},${num(cy)}`,
    `A${num(r)},${num(r)} 0 0 1 ${num(b.x2 - r)},${num(b.y2)}`,
    `H${num(b.x1 + r)}`,
    `A${num(r)},${num(r)} 0 0 1 ${num(b.x1)},${num(cy)}`,
    'Z',
  ].join(' ');
};

/** 任一画布尺寸的路径（坐标已按 k 乘过，不是比例连乘） */
export function shapesAt(P) {
  const g = geom(P);
  return { tri: triPath(g.tri), pill: pillPath(g.badge), g };
}

/** 矢量母版用：不取整 */
export function shapesAtExact(P) {
  const g = geomExact(P);
  return { tri: triPath(g.tri), pill: pillPath(g.badge), g };
}

/** 108 坐标系的路径（VectorDrawable 用） */
export const SHAPES108 = { tri: triPath(L108.tri), pill: pillPath(L108.badge) };
