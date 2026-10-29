// 一个必须先回答的问题：3.847em 是「盒宽」还是「墨宽」？
// 三种画法同屏对照，全部 120px，全部按像素量：
//   A 浏览器文本（opsz 关）  B 我抽的路径  C 浏览器文本（opsz auto）
// 若 A≈B：路径管线可信（含 kerning）。若 A 明显窄于 3.847em：3.847 是盒宽不是墨宽。
import { writeFileSync, readFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { openPage, bboxOfDiff, subpixelEdge } from './.v2-cdp.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const TMP = join(HERE, '.v2-tmp');
mkdirSync(TMP, { recursive: true });

const W = JSON.parse(readFileSync(join(HERE, '.v2-wordmark.json'), 'utf8'));
const F = 120, S = F / W.upm;
const BG = '#000000', INK = '#FFFFFF';
const X0 = 200;

// 把 7 个字母拼成一个 <g>（各自 pen 已在 JSON 里算好）
const gPaths = W.letters.map(l => `<path d="${l.d}"/>`).join('');

const row = (y) => `position:absolute;left:0;top:${y}px;width:1920px;height:180px`;
const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
@font-face{font-family:InterV;src:url("file://${join(ROOT, 'assets/fonts/InterVariable.woff2')}") format("woff2");font-weight:100 900;font-display:block}
html,body{margin:0;padding:0;background:${BG};overflow:hidden}
.t{font:600 ${F}px/1 InterV;letter-spacing:-.02em;font-optical-sizing:none;color:${INK};
   white-space:pre;position:absolute;left:${X0}px;top:0}
</style></head><body>
<div id="rowA" style="${row(100)}"><div class="t" id="ta">LiveHub</div></div>
<div id="rowB" style="${row(400)}">
  <svg width="1200" height="180" viewBox="0 0 1200 180" style="position:absolute;left:0;top:0">
    <g transform="translate(${X0} 140) scale(${S})" fill="${INK}">${gPaths}</g>
  </svg>
</div>
<div id="rowC" style="${row(700)}"><div class="t" id="tc" style="font-optical-sizing:auto">LiveHub</div></div>
</body></html>`;
writeFileSync(join(TMP, 'probe.html'), html);

const page = await openPage({ file: join(TMP, 'probe.html'), port: 9521, readySelector: '#ta' });

const rects = await page.eval(`JSON.stringify({
  A: (()=>{const r=document.getElementById('ta').getBoundingClientRect();return {w:r.width,x:r.left}})(),
  C: (()=>{const r=document.getElementById('tc').getBoundingClientRect();return {w:r.width,x:r.left}})(),
  capPx: null,
  fonts: [...document.fonts].map(f=>f.family+':'+f.status)
})`);

const out = {};
for (const [k, y] of [['A', 100], ['B', 400], ['C', 700]]) {
  const img = await page.shoot({ x: X0 - 40, y, w: 1400, h: 180 });
  const bb = bboxOfDiff(img, [0, 0, 0], 8);
  const left = subpixelEdge(img, [0, 0, 0], 'x', 0, img.h - 1, 0, img.w - 1, 255);
  out[k] = { inkW: bb.x1 - bb.x0 + 1, x0: bb.x0 + X0 - 40, x1: bb.x1 + X0 - 40, n: bb.n, leftEdge: bb.x0 - 40 + X0 };
}

const R = JSON.parse(rects);
if (!R.fonts.join().includes('loaded')) throw new Error('字体没加载成功：' + R.fonts.join(' | '));
console.log('字号 ' + F + 'px   upm ' + W.upm + '   缩放 ' + S.toFixed(8) + ' u→px');
console.log('字体加载：' + R.fonts.join(' | '));
console.log('');
console.log('   A 文本 opsz关   离屏盒宽 ' + R.A.w.toFixed(3) + 'px   墨迹宽 ' + out.A.inkW + 'px   墨迹 x ' + out.A.x0 + '→' + out.A.x1);
console.log('   B 路径           —                      墨迹宽 ' + out.B.inkW + 'px   墨迹 x ' + out.B.x0 + '→' + out.B.x1);
console.log('   C 文本 opsz auto 离屏盒宽 ' + R.C.w.toFixed(3) + 'px   墨迹宽 ' + out.C.inkW + 'px   墨迹 x ' + out.C.x0 + '→' + out.C.x1);
console.log('');
console.log('   盒宽/120 = ' + (R.A.w / F).toFixed(5) + 'em   （08 §3 的 3.8473 对得上就是盒宽）');
console.log('   墨宽/120 = ' + (out.A.inkW / F).toFixed(5) + 'em');
console.log('   A−B 墨宽差 ' + (out.A.inkW - out.B.inkW) + 'px（≈0 说明路径管线的 kerning/advance 与浏览器一致）');
console.log('   C−A 盒宽差 ' + (R.C.w - R.A.w).toFixed(3) + 'px（负值=opsz auto 切了 Display）');

writeFileSync(join(HERE, '.v2-probe.json'), JSON.stringify({ F, S, R, out }, null, 1));
await page.close();
