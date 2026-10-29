// v2 资产构建：SVG 母版 + PNG 五档 + android-res 成品树 + 预览页
// 用法：node design/.v2-build.mjs
// 所有尺寸来自 design/.v2-geom.mjs，本文件不写死任何几何数字（只有文件路径与尺寸清单）。
import { writeFileSync, mkdirSync, copyFileSync, rmSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PALETTE, GEOMS, byKey, WM, KERN, CAP_EM, INK_EM, BOX_EM, fmt, iconSvg, assertAgainstSpec } from './.v2-geom.mjs';
import { openPage } from './.v2-cdp.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const V2 = join(ROOT, 'assets/brand/v2');
const ICON = join(V2, 'icon'), PNG = join(ICON, 'png'), RES = join(V2, 'android-res');
const TMP = join(HERE, '.v2-tmp');
for (const d of [ICON, PNG, RES, TMP, join(RES, 'drawable-xhdpi'), join(RES, 'values'),
  ...['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi'].map(x => join(RES, 'mipmap-' + x))]) mkdirSync(d, { recursive: true });

/* ---------- PNG-32（带 alpha），位图一律 Chrome 按目标尺寸 1:1 光栅化 ---------- */
const CRC = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return t; })();
const crc32 = b => { let c = ~0; for (let i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 255] ^ (c >>> 8); return (~c) >>> 0; };
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
};
function encodePNG32(w, h, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const stride = w * 4, raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (stride + 1)] = 0; rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride); }
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

/* ---------- 1. 规格对照（与主理人表逐格比，>2% 才报） ---------- */
const drift = assertAgainstSpec();

/* ---------- 2. SVG 母版 ---------- */
writeFileSync(join(ICON, 'banner-16x9.svg'), iconSvg(byKey('banner'), { plate: true }));
writeFileSync(join(ICON, 'square.svg'), iconSvg(byKey('square'), { plate: true }));
// 满出血版：Android TV banner 槽位是矩形，系统不裁圆角；烘焙圆角会在四角留透明缺口
writeFileSync(join(ICON, 'banner-16x9-bleed.svg'), iconSvg(byKey('banner'), { bleed: true }));

/* ---------- 3. 光栅化清单 ---------- */
const TARGETS = [];
const banner = byKey('banner'), square = byKey('square');
for (const w of [640, 480, 320, 240, 160]) TARGETS.push({ id: `banner-${w}`, g: banner, w, h: Math.round(w * 180 / 320), plate: true });
for (const w of [512, 192, 144, 96, 72, 48]) TARGETS.push({ id: `square-${w}`, g: square, w, h: w, plate: true });
// 审计用满出血（不透明），不是交付件
TARGETS.push({ id: 'audit-banner-320', g: banner, w: 320, h: 180, bleed: true, audit: true });
TARGETS.push({ id: 'audit-square-512', g: square, w: 512, h: 512, bleed: true, audit: true });

let yy = 0;
for (const t of TARGETS) { t.y = yy; yy += t.h + 24; }
const PAGE_H = yy + 24;

const cell = t => `<div style="position:absolute;left:0;top:${t.y}px;width:${t.w}px;height:${t.h}px;font-size:0;line-height:0">`
  + iconSvg(t.g, { bleed: !!t.bleed, out: { w: t.w, h: t.h } })
  + `</div>`;

writeFileSync(join(TMP, 'raster.html'),
  `<!DOCTYPE html><html><head><meta charset="utf-8"><style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}</style></head><body>
${TARGETS.map(cell).join('\n')}
</body></html>`);

/* ---------- 4. 截图 ---------- */
const page = await openPage({ file: join(TMP, 'raster.html'), port: 9531, width: 1280, height: Math.min(PAGE_H, 4000) });
await page.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: PAGE_H, deviceScaleFactor: 1, mobile: false });
await page.send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
await new Promise(r => setTimeout(r, 400));

const out = {};
for (const t of TARGETS) {
  const img = await page.shoot({ x: 0, y: t.y, w: t.w, h: t.h });
  if (img.w !== t.w || img.h !== t.h) throw new Error(`${t.id} 尺寸 ${img.w}×${img.h}`);
  // 满出血件 Chrome 会把 alpha 通道优化掉（全 255），补回来即可
  const rgba = img.ch === 4 ? img.data : (() => {
    const b = Buffer.alloc(t.w * t.h * 4);
    for (let i = 0; i < t.w * t.h; i++) { b[i * 4] = img.data[i * 3]; b[i * 4 + 1] = img.data[i * 3 + 1]; b[i * 4 + 2] = img.data[i * 3 + 2]; b[i * 4 + 3] = 255; }
    return b;
  })();
  if (t.audit) { out[t.id] = { ...img, data: rgba, ch: 4 }; continue; }
  const buf = encodePNG32(t.w, t.h, rgba);
  writeFileSync(join(PNG, `${t.id}.png`), buf);
  out[t.id] = { md5: md5(buf), bytes: buf.length };
}
await page.close();
rmSync(TMP, { recursive: true, force: true });

/* 满出血 TV banner：不透明，直接落 android-res */
{
  const img = out['audit-banner-320'];
  const rgb = Buffer.alloc(320 * 180 * 3);
  let alphaBad = 0;
  for (let i = 0; i < 320 * 180; i++) {
    const a = img.data[i * 4 + 3];
    if (a !== 255) alphaBad++;
    rgb[i * 3] = img.data[i * 4]; rgb[i * 3 + 1] = img.data[i * 4 + 1]; rgb[i * 3 + 2] = img.data[i * 4 + 2];
  }
  if (alphaBad) throw new Error(`tv_banner 有 ${alphaBad} 个非不透明像素`);
  // 用 PNG-24 写出：TV banner 必须满出血不透明
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(320, 0); ihdr.writeUInt32BE(180, 4); ihdr[8] = 8; ihdr[9] = 2;
  const stride = 320 * 3, raw = Buffer.alloc((stride + 1) * 180);
  for (let y = 0; y < 180; y++) { raw[y * (stride + 1)] = 0; rgb.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride); }
  const buf = Buffer.concat([Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
  writeFileSync(join(RES, 'drawable-xhdpi/tv_banner.png'), buf);
  out['tv_banner'] = { md5: md5(buf), bytes: buf.length };
}

/* ---------- 5. android-res 成品树 ---------- */
const BUCKET = { 48: 'mdpi', 72: 'hdpi', 96: 'xhdpi', 144: 'xxhdpi', 192: 'xxxhdpi' };
for (const [px, b] of Object.entries(BUCKET)) copyFileSync(join(PNG, `square-${px}.png`), join(RES, `mipmap-${b}/ic_launcher.png`));
writeFileSync(join(RES, 'values/tv_banner_colors.xml'), `<?xml version="1.0" encoding="utf-8"?>
<!-- 单独文件名：不要覆盖宿主已有的 values/colors.xml -->
<resources>
    <color name="tv_banner_background">${PALETTE.icon}</color>
</resources>
`);

function md5(b) { return createHash('md5').update(b).digest('hex'); }

/* ---------- 6. 打印 ---------- */
const pad = (s, n) => String(s).padEnd(n);
console.log('\nv2 三套几何（F=字号，墨宽=字标实际墨迹，与主理人表对照）');
console.log('档位     画布      圆角    cap    字号 F   墨宽    墨宽/宽  留白    cap顶  baseline  偏差');
console.log('─'.repeat(104));
for (const g of GEOMS) {
  const d = drift.find(x => x.key === g.key).drift;
  console.log(
    pad(g.key, 9), pad(`${g.W}×${g.H}`, 10), pad(g.r, 7), pad(g.cap.toFixed(2), 7), pad(g.F.toFixed(2), 9),
    pad(g.inkW.toFixed(2), 8), pad((100 * g.pctW).toFixed(2) + '%', 9), pad(g.margins.toFixed(2), 7),
    pad(g.capTop.toFixed(2), 7), pad(g.baseline.toFixed(2), 9), d.length ? d.join('；') : '在容差内');
}
console.log(`\n字标度量：upm ${WM.upm} | cap ${CAP_EM.toFixed(6)}em | kern ${KERN.totalKern}u | 墨宽 ${INK_EM.toFixed(6)}em | 盒宽 ${BOX_EM.toFixed(6)}em`);
console.log(`Live/Hub 分界：相对墨迹左缘 ${KERN.boundaryPct.toFixed(4)}%（参考图 50.0%）｜相对排版盒 ${(100 * KERN.penKern[4] / KERN.boxWidth).toFixed(4)}%`);
console.log('\nPNG 产物（md5 / 字节）');
for (const [k, v] of Object.entries(out)) if (v.md5) console.log('  ' + pad(k + '.png', 18), v.md5, String(v.bytes).padStart(7));
