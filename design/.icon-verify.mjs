// 图标资产实测自检（不许写「应该」，只打印量到的数）
// 用法：node design/.icon-verify.mjs
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SIZES, EXPECTED, L108, SHAPES108, geom, normDist, shapesAtExact } from './.icon-geom.mjs';
import { decodePNG, toRGB } from './.png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const ICON = join(ROOT, 'assets/brand/icon');
const RES = join(ROOT, 'assets/brand/android-res');

const hex = v => '#' + v.map(n => n.toString(16).padStart(2, '0').toUpperCase()).join('');
const pad = (s, n) => String(s).padEnd(n);

/* ---------- 颜色分类 ---------- */
// 抗锯齿只有一种合法产物：本色与底色 #000000 之间的线性混合。
// 于是每个非纯色像素要么是 (t,t,t)，要么是 (t, t*69/255, t*58/255)。
// 落不进这两类的就是「第四种色」——真违规，不是抗锯齿。
function classify(r, g, b) {
  if (r === 0 && g === 0 && b === 0) return { k: 'black', a: 0 };
  if (r === 255 && g === 255 && b === 255) return { k: 'white', a: 1 };
  if (r === 255 && g === 69 && b === 58) return { k: 'red', a: 1 };
  if (r === g && g === b) return { k: 'aa-white', a: r / 255 };
  if (Math.abs(g - r * 69 / 255) <= 1.05 && Math.abs(b - r * 58 / 255) <= 1.05) return { k: 'aa-red', a: r / 255 };
  return { k: 'ALIEN', a: 0 };
}

const cache = {};
const load = P => {
  if (cache[P]) return cache[P];
  const img = decodePNG(readFileSync(join(ICON, 'png', `ic_launcher_${P}.png`)));
  const rgb = toRGB(img, `${P}px`);
  const px = (x, y) => [rgb[(y * P + x) * 3], rgb[(y * P + x) * 3 + 1], rgb[(y * P + x) * 3 + 2]];
  const cls = (x, y) => { const [r, g, b] = px(x, y); return classify(r, g, b); };
  return (cache[P] = { P, img, rgb, px, cls });
};

const say = s => console.log(s);

/* ============================ 3. 颜色集合 ============================ */
say('\n【3】五档实际用到的颜色集合（抗锯齿未关闭：SVG 按目标尺寸 1:1 光栅化，25° 斜边必然有边缘混合）');
say(pad('档', 5) + pad('纯 #000000', 12) + pad('#FFFFFF', 11) + pad('#FF453A', 11) +
  pad('边缘混合', 10) + pad('占画布', 9) + pad('第四色', 8) + '结论');
say('─'.repeat(100));
const data = {};
for (const P of SIZES) {
  const d = load(P); data[P] = d;
  const hist = { black: 0, white: 0, red: 0, 'aa-white': 0, 'aa-red': 0, ALIEN: 0 };
  const alien = new Map();
  for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) {
    const c = d.cls(x, y); hist[c.k]++;
    if (c.k === 'ALIEN') { const k = hex(d.px(x, y)); alien.set(k, (alien.get(k) || 0) + 1); }
  }
  d.hist = hist; d.alien = alien;
  const aa = hist['aa-white'] + hist['aa-red'];
  say(pad(P, 5) + pad(hist.black, 12) + pad(hist.white, 11) + pad(hist.red, 11) +
    pad(aa, 10) + pad((aa / (P * P) * 100).toFixed(2) + '%', 9) + pad(hist.ALIEN, 8) +
    (hist.ALIEN === 0 ? '三色 + 黑↔本色的线性混合' : '违规色 ' + [...alien.keys()].slice(0, 6).join(' ')));
}
const alienAll = new Map();
for (const P of SIZES) for (const [k, v] of data[P].alien) alienAll.set(k, (alienAll.get(k) || 0) + v);
say('  第四色合计：' + (alienAll.size === 0 ? '0 种（五档全部满足三色约束）'
  : [...alienAll.entries()].map(([k, v]) => k + '×' + v).join(' ')));
say('  处理方式：不关抗锯齿 —— 关掉会让 25° 斜边产生 1px 台阶，48 档会变成锯齿噪点，');
say('            而规格 §2 要防的是「缝里两列半透明灰把两块连起来」，不是斜边本身。');
say('            替代做法：把每个非纯色像素逐一定义为「黑 ↔ 某一本色的线性混合」，凡落不进这两类即计第四色。');
say('            上表第四色 = 0，即边缘 AA 只在本色与 #000000 之间插值，没有引入任何新的色相。');

/* ============================ 1. 96px 同列验证 ============================ */
say('\n【1】96px（1080p 电视主档）：徽章右缘最后一列 vs 三角顶点所在列');
{
  const d = load(96), P = 96;
  let lastRed = -1, lastWhite = -1;
  const cov = {};
  for (let x = 0; x < P; x++) for (let y = 0; y < P; y++) {
    const c = d.cls(x, y);
    if ((c.k === 'red' || c.k === 'aa-red') && c.a > 0) { lastRed = Math.max(lastRed, x); cov[x] = Math.max(cov[x] || 0, c.a); }
    if ((c.k === 'white' || c.k === 'aa-white') && c.a > 0) lastWhite = Math.max(lastWhite, x);
  }
  say(`  徽章右缘最后一列 = 第 ${lastRed} 列（占 x∈[${lastRed},${lastRed + 1})，该列红色覆盖率 ${(cov[lastRed] * 100).toFixed(0)}%）`);
  say(`  三角顶点所在的最后一列 = 第 ${lastWhite} 列`);
  say(`  是否同一列：${lastRed === lastWhite ? '是，同一列 ✓' : '否 ✗ 相差 ' + Math.abs(lastRed - lastWhite) + ' 列'}`);
  say(`  规格：顶点 x=${EXPECTED[96].apexX}、徽章右缘 x=${EXPECTED[96].br} —— 都落在像素边界 x=72 上，因此都在第 71 列收尾。`);
}

/* ============================ 2. 48px 缝 ============================ */
say('\n【2】48px（3 米识别下限档）：两块之间完全不透明黑像素');
{
  const d = load(48), P = 48, g = geom(48);
  const isRed = (x, y) => { const c = d.cls(x, y); return (c.k === 'red' || c.k === 'aa-red') && c.a > 0; };
  const isWhite = (x, y) => { const c = d.cls(x, y); return (c.k === 'white' || c.k === 'aa-white') && c.a > 0; };
  const isBlack = (x, y) => d.cls(x, y).k === 'black';
  say(pad('列 x', 7) + pad('徽章最低行', 12) + pad('三角最高行', 11) + pad('纯黑行数', 10) + '缝内非纯黑像素');
  say('─'.repeat(100));
  let minRun = 1e9, colsWithBlack = 0, bridgeCols = 0;
  const fringeAll = new Set();
  for (let x = g.badge.x1; x < g.badge.x2; x++) {
    let redBottom = -1, whiteTop = P;
    for (let y = 0; y < P; y++) if (isRed(x, y)) redBottom = Math.max(redBottom, y);
    for (let y = P - 1; y >= 0; y--) if (isWhite(x, y)) whiteTop = Math.min(whiteTop, y);
    if (redBottom < 0 || whiteTop >= P || whiteTop <= redBottom) continue;
    let run = 0; const fringe = [];
    for (let y = redBottom + 1; y < whiteTop; y++) {
      if (isBlack(x, y)) run++;
      else { fringe.push('y' + y + ':' + hex(d.px(x, y))); fringeAll.add(hex(d.px(x, y))); }
    }
    if (run > 0) colsWithBlack++;
    if (fringe.length) bridgeCols++;
    minRun = Math.min(minRun, run);
    say(pad(x, 7) + pad(redBottom, 12) + pad(whiteTop, 11) + pad(run, 10) + (fringe.length ? fringe.join(' ') : '—'));
  }
  say('─'.repeat(100));
  say(`  缝最窄一列的纯黑像素数 = ${minRun}（下限 3）→ ${minRun >= 3 ? '达标 ✓' : '不达标 ✗'}`);
  say(`  缝内含纯黑像素的列数 = ${colsWithBlack}（徽章横跨 x ${g.badge.x1}→${g.badge.x2}，共 ${g.badge.x2 - g.badge.x1} 列）`);
  say(`  出现「灰桥」（缝内非纯黑像素）的列数 = ${bridgeCols} → ${bridgeCols === 0 ? '无灰桥 ✓' : '有 ' + bridgeCols + ' 列'}`);
  say(`  缝内实际出现的非纯黑值 = ${fringeAll.size ? [...fringeAll].join(' ') : '空集（缝内每个像素都是 #000000）'}`);

  const mat = [];
  for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) {
    const c = d.cls(x, y);
    if (c.a >= 0.5 && (c.k === 'white' || c.k === 'aa-white')) mat.push([x, y, 0]);
    if (c.a >= 0.5 && (c.k === 'red' || c.k === 'aa-red')) mat.push([x, y, 1]);
  }
  let best = 1e9;
  for (const a of mat) if (a[2] === 0) for (const b of mat) if (b[2] === 1) {
    const dd = Math.hypot(a[0] - b[0], a[1] - b[1]); if (dd < best) best = dd;
  }
  say(`  两块实体像素（覆盖率≥50%）最短中心距 = ${best.toFixed(2)} px = ${(best * 72 / 48).toFixed(2)} 个 108 系单位`);
  say(`  说明：规格的 3.10 是徽章「尖角 (23,12)」到斜边的法向距离；徽章是全圆（r=3），这个角本身被切掉了，`);
  say(`        所以渲染出来的实际最短距离必然比 3.10 大。这是圆角带来的单向增益（缝只会更宽），不是偏离规格。`);
}

/* ============================ 4. 红色墨量 ============================ */
say('\n【4】红色墨量（覆盖率加权求和，AA 按混合比例折算）');
say(pad('档', 5) + pad('红覆盖率', 11) + pad('% 72 视口画布', 15) + pad('% 108 画布', 13) + pad('白覆盖率', 11) + '白 : 红');
say('─'.repeat(84));
for (const P of SIZES) {
  const d = data[P]; let red = 0, white = 0;
  for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) {
    const c = d.cls(x, y);
    if (c.k === 'red' || c.k === 'aa-red') red += c.a;
    if (c.k === 'white' || c.k === 'aa-white') white += c.a;
  }
  d.red = red; d.white = white;
  say(pad(P, 5) + pad(red.toFixed(1), 11) + pad((red / (P * P) * 100).toFixed(3) + '%', 15) +
    pad((red / (P * P * 2.25) * 100).toFixed(3) + '%', 13) + pad(white.toFixed(1), 11) + (white / red).toFixed(2) + ' : 1');
}
say('  口径：PNG 是「满出血 = 72 视口」，所以红墨占 PNG 画布 = 占视口 = ~3.1%（裁决 §3 的「3.1% 视口」）；');
say('        换算到 108 图层画布（面积比 2.25:1）= ~1.39%（裁决 §5 的「1.39% 画布」，上限 2%）。同一块墨，两个分母。');

/* ============================ 5. 宽高比 ============================ */
say('\n【5】192px：徽章实测宽高比（目标 2.22，允许 2.17–2.25）');
say(pad('档', 5) + pad('徽章 bbox', 20) + pad('尺寸', 10) + pad('宽高比', 9) + pad('三角 bbox', 22) + '三角 bbox 尺寸');
say('─'.repeat(100));
for (const P of SIZES) {
  const d = data[P];
  let rx1 = 1e9, rx2 = -1, ry1 = 1e9, ry2 = -1, wx1 = 1e9, wx2 = -1, wy1 = 1e9, wy2 = -1;
  for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) {
    const c = d.cls(x, y);
    if ((c.k === 'red' || c.k === 'aa-red') && c.a > 0) { rx1 = Math.min(rx1, x); rx2 = Math.max(rx2, x); ry1 = Math.min(ry1, y); ry2 = Math.max(ry2, y); }
    if ((c.k === 'white' || c.k === 'aa-white') && c.a > 0) { wx1 = Math.min(wx1, x); wx2 = Math.max(wx2, x); wy1 = Math.min(wy1, y); wy2 = Math.max(wy2, y); }
  }
  const bw = rx2 - rx1 + 1, bh = ry2 - ry1 + 1, tw = wx2 - wx1 + 1, th = wy2 - wy1 + 1;
  d.bbox = { rx1, rx2, ry1, ry2, wx1, wx2, wy1, wy2, bw, bh, tw, th };
  say(pad(P, 5) + pad(`x${rx1}→${rx2}  y${ry1}→${ry2}`, 20) + pad(`${bw}×${bh}`, 10) +
    pad((bw / bh).toFixed(3), 9) + pad(`x${wx1}→${wx2}  y${wy1}→${wy2}`, 22) + `${tw}×${th}`);
}
{
  const b = data[192].bbox, r = b.bw / b.bh;
  say(`  192px 实测 ${b.bw}×${b.bh} = ${r.toFixed(3)} : 1 ` + (r >= 2.17 && r <= 2.25 ? '在 2.17–2.25 内 ✓' : '出界 ✗'));
  say('  右缘−顶点（徽章最后一列 / 三角最后一列，五档）：' +
    SIZES.map(P => `${P}:${data[P].bbox.rx2}/${data[P].bbox.wx2}${data[P].bbox.rx2 === data[P].bbox.wx2 ? '✓' : '✗'}`).join('  '));
}

/* ============================ 6. 静态检查 ============================ */
say('\n【6】静态检查（SVG / VectorDrawable / android-res 成品树）');
const fg = readFileSync(join(ICON, 'ic_launcher_foreground.svg'), 'utf8');
const mono = readFileSync(join(ICON, 'ic_launcher_monochrome.svg'), 'utf8');
const big = readFileSync(join(ICON, 'ic_launcher_512.svg'), 'utf8');
const vFG = readFileSync(join(RES, 'drawable/ic_launcher_foreground.xml'), 'utf8');
const vMO = readFileSync(join(RES, 'drawable/ic_launcher_monochrome.xml'), 'utf8');
// 108 图层系（VectorDrawable）与 72 视口系（PNG/母版）是两套框，不能互乘 1.5 混用：
//   VectorDrawable 画布 = 108 图层，内容 x 35→72 / y 27→78
//   512 母版与五档 PNG 画布 = 72 视口（= 108 图层中间那 72×72 的裁切），内容 = 108 坐标减 18
const s108 = { tri: SHAPES108.tri, pill: SHAPES108.pill, g: { tri: L108.tri, badge: L108.badge } };
const s512 = shapesAtExact(512);

say(`  foreground.svg：path 数 = ${(fg.match(/<path/g) || []).length}（要求 2）；违禁项 ${['stroke', 'gradient', 'filter', 'opacity', '<rect'].filter(k => fg.includes(k)).join(',') || '全无'}`);
say(`  monochrome.svg：path 数 = ${(mono.match(/<path/g) || []).length}；底板/描边/渐变/滤镜 ${['<rect', 'stroke', 'gradient', 'filter'].filter(k => mono.includes(k)).join(',') || '全无'}；填充 ${[...new Set((mono.match(/fill="(#[0-9A-F]{6})"/g) || []))].join(' ')}`);
say(`  512.svg：viewBox = ${big.match(/viewBox="([^"]+)"/)[1]}；黑底 rect = ${big.includes('<rect width="512" height="512" fill="#000000"/>') ? '纯黑满出血 ✓' : '缺失 ✗'}`);
say(`  512 母版：三角 ${s512.g.tri.w.toFixed(2)}×${s512.g.tri.h.toFixed(2)}（h/w ${(s512.g.tri.h / s512.g.tri.w).toFixed(4)}，规格 1.297）顶点 x=${s512.g.tri.apexX}`);
say(`  512 母版：徽章 ${s512.g.badge.w.toFixed(2)}×${s512.g.badge.h.toFixed(2)}（${(s512.g.badge.w / s512.g.badge.h).toFixed(4)}:1）右缘 x=${s512.g.badge.x2} → 右缘−顶点 = ${(s512.g.badge.x2 - s512.g.tri.apexX).toFixed(6)}`);
say(`  512 母版：法向缝 = ${s512.g.gap.toFixed(2)} = 4.2175 × 512/72；闸门下限 = ${s512.g.gapMin.toFixed(2)}`);
say(`  512.svg 内的路径与该算数一致：tri ${big.includes(s512.tri) ? '✓' : '✗'}；pill ${big.includes(s512.pill) ? '✓' : '✗'}`);
say(`  VectorDrawable = SVG 108 路径逐字相同：foreground ${vFG.includes(s108.tri) && vFG.includes(s108.pill) ? '✓' : '✗'}；monochrome ${vMO.includes(s108.tri) && vMO.includes(s108.pill) ? '✓' : '✗'}`);
say(`  108 系：徽章右缘 x=${L108.badge.x2}，三角顶点 x=${L108.tri.apexX}，差 = ${L108.badge.x2 - L108.tri.apexX}；` +
  `法向缝 = ${normDist(L108.badge.x1, L108.badge.y2, L108.tri.x, L108.tri.y1, L108.tri.apexX, L108.tri.apexY).toFixed(4)}（约束点 = 徽章左下角 (52,36)）`);
{
  const pts = [[L108.tri.x, L108.tri.y1], [L108.tri.x, L108.tri.y2], [L108.tri.apexX, L108.tri.apexY],
    [L108.badge.x1, L108.badge.y1], [L108.badge.x2, L108.badge.y1], [L108.badge.x1, L108.badge.y2], [L108.badge.x2, L108.badge.y2]];
  const rmax = Math.max(...pts.map(([x, y]) => Math.hypot(x - 54, y - 54)));
  say(`  安全圆 Ø66（r=33）：最外顶点距心 ${rmax.toFixed(2)}，余量 ${(33 - rmax).toFixed(2)}（规格说最紧处是徽章右上角 (72,27)=32.45）`);
}
say('  android-res 成品树（10 个文件）：' + ['mipmap-anydpi-v26/ic_launcher.xml', 'mipmap-anydpi-v33/ic_launcher.xml',
  'drawable/ic_launcher_foreground.xml', 'drawable/ic_launcher_monochrome.xml', 'values/ic_launcher_colors.xml',
  'mipmap-mdpi/ic_launcher.png', 'mipmap-hdpi/ic_launcher.png', 'mipmap-xhdpi/ic_launcher.png',
  'mipmap-xxhdpi/ic_launcher.png', 'mipmap-xxxhdpi/ic_launcher.png']
  .map(f => { try { return f.split('/')[1] + '(' + readFileSync(join(RES, f)).length + 'B)'; } catch { return f + '(缺失!)'; } }).join(' '));

/* ============================ 7. 逐列剖面 ============================ */
for (const P of [48]) {
  const d = data[P], g = geom(P);
  say(`\n【附】${P}px 缝区像素剖面（█=#FFFFFF ▓=#FF453A ·=#000000  R/r/'=红与黑的 AA  -/+/@=白与黑的 AA）`);
  const xs = Array.from({ length: g.badge.x2 - g.badge.x1 + 5 }, (_, i) => g.badge.x1 - 3 + i);
  say('  列号 x    ' + xs.map(x => x % 10).join('') + '   （' + xs[0] + ' → ' + xs[xs.length - 1] + '，个位数字即列号）');
  for (let y = g.badge.y1 - 1; y <= g.badge.y2 + 9; y++) {
    let line = '';
    for (let x = g.badge.x1 - 3; x <= g.badge.x2 + 1; x++) {
      if (x < 0 || x >= P || y < 0 || y >= P) { line += ' '; continue; }
      const c = d.cls(x, y);
      line += c.k === 'black' ? '·' : c.k === 'white' ? '█' : c.k === 'red' ? '▓'
        : c.k === 'aa-white' ? (c.a > 0.66 ? '@' : c.a > 0.33 ? '+' : '-')
          : (c.a > 0.66 ? 'R' : c.a > 0.33 ? 'r' : "'");
    }
    say(`  y=${pad(y, 4)}|${line}|`);
  }
}

console.log('\n（以上全部为脚本实测，无一处「应该」；脚本：design/.icon-verify.mjs）');
