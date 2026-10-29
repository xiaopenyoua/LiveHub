// 图标资产构建：SVG 母版 + 五档 PNG + android-res 成品树
// 用法：node design/.icon-build.mjs
// 一切坐标来自 design/.icon-geom.mjs，本文件不写死任何几何数字。
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, copyFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SIZES, EXPECTED, PALETTE, SHAPES108, shapesAt, shapesAtExact } from './.icon-geom.mjs';
import { decodePNG, encodePNG24, toRGB } from './.png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const ICON = join(ROOT, 'assets/brand/icon');
const PNG = join(ICON, 'png');
const RES = join(ROOT, 'assets/brand/android-res');
const TMP = join(HERE, '.icon-tmp');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9461;

for (const d of [ICON, PNG, RES, TMP,
  join(RES, 'drawable'), join(RES, 'values'),
  join(RES, 'mipmap-anydpi-v26'), join(RES, 'mipmap-anydpi-v33'),
  ...['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi'].map(x => join(RES, 'mipmap-' + x)),
]) mkdirSync(d, { recursive: true });

/* ---------- 1. 规格断言：规矩算出来的必须等于 02 表格写的 ---------- */
const rows = [];
for (const P of SIZES) {
  const c = shapesAt(P).g, e = EXPECTED[P];
  const got = { tw: c.tri.w, th: c.tri.h, bx: c.tri.x, apexX: c.tri.apexX, apexY: c.tri.apexY,
    bw: c.badge.w, bh: c.badge.h, bl: c.badge.x1, br: c.badge.x2, gap: c.gap, gapMin: c.gapMin };
  for (const key of Object.keys(e)) {
    const a = got[key], b = e[key];
    const ok = key === 'gap' ? Math.abs(a - b) <= 0.006 : a === b;
    if (!ok) throw new Error(`${P}px ${key}: 算得 ${a} ≠ 规格 ${b}`);
  }
  if (c.badge.x2 !== c.tri.apexX) throw new Error(`${P}px 右缘≠顶点`);
  if (c.badge.x2 !== e.br) throw new Error(`${P}px 徽章右缘跑位`);
  rows.push({ P, ...e, hw: (e.th / e.tw).toFixed(3), ratio: (e.bw / e.bh).toFixed(3) });
}

/* ---------- 2. SVG 生成 ---------- */
const svg = (w, h, vb, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="${vb}">\n${body}\n</svg>\n`;

const twoPaths = (s, white, red) =>
  `  <path fill="${white}" d="${s.tri}"/>\n  <path fill="${red}" d="${s.pill}"/>`;

// 2a. 前景层（108 坐标，底色透明，只有两个 path）
writeFileSync(join(ICON, 'ic_launcher_foreground.svg'),
  svg(108, 108, '0 0 108 108', twoPaths(SHAPES108, PALETTE.white, PALETTE.red)));

// 2b. 单色层（同样的两个 path，单色填充，无底板；缝 = 两块互不相交 → alpha 0）
writeFileSync(join(ICON, 'ic_launcher_monochrome.svg'),
  svg(108, 108, '0 0 108 108', twoPaths(SHAPES108, PALETTE.white, PALETTE.white)));

// 2c. 512 商店母版（纯黑满出血 + 两块墨）
{
  const P = 512, s = shapesAtExact(P);   // 母版是矢量：坐标不取整，只有位图才取整
  writeFileSync(join(ICON, 'ic_launcher_512.svg'), svg(P, P, `0 0 ${P} ${P}`,
    `  <rect width="${P}" height="${P}" fill="${PALETTE.black}"/>\n` + twoPaths(s, PALETTE.white, PALETTE.red)));
}

/* ---------- 3. android-res 成品树（VectorDrawable，108 坐标） ---------- */
const vector = (fillWhite, fillRed) => `<?xml version="1.0" encoding="utf-8"?>
<!-- LiveHub 在播键 · 108 图层系（viewport 108，内容 x 35→72 / y 27→78）
     只用两个 path：不画底板、不画安全圆、不画容器。底色由 @color/ic_launcher_background 铺。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="${fillWhite}"
        android:pathData="${SHAPES108.tri}" />
    <path
        android:fillColor="${fillRed}"
        android:pathData="${SHAPES108.pill}" />
</vector>
`;
writeFileSync(join(RES, 'drawable/ic_launcher_foreground.xml'), vector(PALETTE.white, PALETTE.red));
writeFileSync(join(RES, 'drawable/ic_launcher_monochrome.xml'), vector(PALETTE.white, PALETTE.white));

writeFileSync(join(RES, 'values/ic_launcher_colors.xml'), `<?xml version="1.0" encoding="utf-8"?>
<!-- 单独文件名：不要覆盖宿主已有的 values/colors.xml -->
<resources>
    <color name="ic_launcher_background">${PALETTE.black}</color>
</resources>
`);

const adaptive = (withMono) => `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />${withMono ? `
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />` : ''}
</adaptive-icon>
`;
writeFileSync(join(RES, 'mipmap-anydpi-v26/ic_launcher.xml'), adaptive(false));
writeFileSync(join(RES, 'mipmap-anydpi-v33/ic_launcher.xml'), adaptive(true));

/* ---------- 5. Chrome 按目标尺寸 1:1 光栅化（不做位图缩放） ---------- */
const X = {}; { let x = 0; for (const P of SIZES) { X[P] = x; x += P + 40; } }
const PAGESVG = P => {
  const s = shapesAt(P);
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${P}" height="${P}" viewBox="0 0 ${P} ${P}" shape-rendering="geometricPrecision">
<rect width="${P}" height="${P}" fill="${PALETTE.black}"/>
<path fill="${PALETTE.white}" d="${s.tri}"/>
<path fill="${PALETTE.red}" d="${s.pill}"/>
</svg>`;
};
const tmpHtml = join(TMP, 'render.html');
writeFileSync(tmpHtml, `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;padding:0;background:#000;overflow:hidden}
.d{position:absolute;top:0;font-size:0;line-height:0}
</style></head><body>
${SIZES.map(P => `<div class="d" id="p${P}" style="left:${X[P]}px;width:${P}px;height:${P}px">${PAGESVG(P)}</div>`).join('\n')}
</body></html>`);

const wait = ms => new Promise(r => setTimeout(r, ms));

async function render() {
  const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--force-color-profile=srgb', '--force-device-scale-factor=1',
    `--remote-debugging-port=${PORT}`, '--window-size=1920,1080', 'file://' + tmpHtml], { stdio: 'ignore' });
  let target = null;
  for (let i = 0; i < 50; i++) {
    await wait(300);
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
      target = list.find(t => t.type === 'page' && t.url.includes('render.html'));
      if (target) break;
    } catch { }
  }
  if (!target) throw new Error('连不上 Chrome');
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(r => ws.addEventListener('open', r, { once: true }));
  let id = 0; const pending = new Map();
  ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } });
  const send = (method, params = {}) => new Promise(res => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });

  await send('Emulation.setDeviceMetricsOverride', { width: 1920, height: 1080, deviceScaleFactor: 1, mobile: false });
  await wait(700);

  const out = {};
  for (const P of SIZES) {
    const r = await send('Page.captureScreenshot', {
      format: 'png', captureBeyondViewport: false,
      clip: { x: X[P], y: 0, width: P, height: P, scale: 1 },
    });
    const img = decodePNG(Buffer.from(r.result.data, 'base64'));
    if (img.w !== P || img.h !== P) throw new Error(`${P}px 截图尺寸 ${img.w}×${img.h}`);
    // 底色是不透明 rect，alpha 必须全 255；不为 255 直接报错，不静默合成
    const rgb = toRGB(img, `${P}px`);
    out[P] = rgb;
    writeFileSync(join(PNG, `ic_launcher_${P}.png`), encodePNG24(P, P, rgb));
  }
  ws.close(); proc.kill();
  return out;
}

const px = await render();
rmSync(TMP, { recursive: true, force: true });

/* ---------- 6. 拷进 android-res 五档密度桶（不二次缩放） ---------- */
const BUCKET = { 48: 'mdpi', 72: 'hdpi', 96: 'xhdpi', 144: 'xxhdpi', 192: 'xxxhdpi' };
for (const P of SIZES) copyFileSync(join(PNG, `ic_launcher_${P}.png`), join(RES, `mipmap-${BUCKET[P]}/ic_launcher.png`));

/* ---------- 7. 打印 ---------- */
console.log('档位  三角 w×h   底边x  顶点      徽章 w×h    徽章 x      缝     缝下限  闸门');
console.log('─'.repeat(84));
for (const r of rows) {
  console.log(
    String(r.P).padEnd(6),
    `${String(r.tw).padStart(3)} × ${String(r.th).padStart(3)}`.padEnd(12),
    String(r.bx).padEnd(6),
    `(${r.apexX},${r.apexY})`.padEnd(10),
    `${String(r.bw).padStart(3)} × ${String(r.bh).padStart(2)}`.padEnd(12),
    `${r.bl}→${r.br}`.padEnd(11),
    r.gap.toFixed(2).padEnd(6),
    r.gapMin.toFixed(2).padEnd(8),
    EXPECTED[r.P] && geom_gate(r.P));
}
function geom_gate(P) { return shapesAt(P).g.gate ? '左移1px' : '未触发'; }
console.log('\n1024 检查：徽章右缘==顶点 x 五档全部成立（差 0）');
console.log('产物：assets/brand/icon/*.svg、png/*.png、assets/brand/android-res/');
