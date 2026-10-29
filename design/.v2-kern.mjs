// kerning 采集：字标的 7 个笔位不要用「纯进宽累加」算 —— 浏览器/Compose 排版会开 kerning。
// 这里让浏览器自己排 7 个前缀串，反推每个字母的真实笔位，差值就是 kerning。
// 产物：design/.v2-wordmark.kern.json（不覆盖 .v2-wordmark.json）
import { writeFileSync, readFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { openPage } from './.v2-cdp.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const TMP = join(HERE, '.v2-tmp');
mkdirSync(TMP, { recursive: true });

const W = JSON.parse(readFileSync(join(HERE, '.v2-wordmark.json'), 'utf8'));
const TEXT = W.text, N = TEXT.length;
const F = 1000, LS = -0.02 * F;                 // −20px @1000px
const FONT = join(ROOT, 'assets/fonts/InterVariable.woff2');

const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
@font-face{font-family:InterV;src:url("file://${FONT}") format("woff2");font-weight:100 900;font-display:block}
html,body{margin:0;padding:0;background:#000}
.p{font:600 ${F}px/1 InterV;letter-spacing:${LS / F}em;font-optical-sizing:none;
   font-kerning:normal;white-space:pre;position:absolute;left:-90000px;top:0;color:#fff}
</style></head><body>
${Array.from({ length: N + 1 }, (_, k) => `<span class="p" id="p${k}">${TEXT.slice(0, k) || '<i>&#8203;</i>'}</span>`).join('\n')}
</body></html>`;
writeFileSync(join(TMP, 'kern.html'), html);

const page = await openPage({ file: join(TMP, 'kern.html'), port: 9522, readySelector: '#p' + N });
const fonts = await page.eval(`JSON.stringify([...document.fonts].map(f=>f.family+':'+f.status))`);
if (!fonts.includes('loaded')) throw new Error('字体没加载：' + fonts);

// P(k) = 前 k 个字符的排版宽度。注意 P(k) 已含第 k 个字符自身的尾字距，
// 所以第 k 个字母的笔位就等于 P(k)，不要再减一次 ls（减了就是每字 +40.96u 的假 kerning）。
const P = JSON.parse(await page.eval(`JSON.stringify(Array.from({length:${N + 1}},(_,k)=>
  document.getElementById('p'+k).getBoundingClientRect().width))`));
await page.close();

const px2u = W.upm / F;
const penKern = P.slice(0, N).map(v => v * px2u);          // 含 kerning 的笔位（units）
const penPure = W.letters.map(l => l.penPure);             // 纯进宽累加（units）

const pairs = [];
let prevCum = 0;
for (let k = 1; k < N; k++) {
  const kd = penKern[k] - penPure[k];
  pairs.push({ pair: TEXT[k - 1] + TEXT[k], kern: +(kd - prevCum).toFixed(3), cum: +kd.toFixed(3) });
  prevCum = kd;
}
const totalKern = penKern[N - 1] - penPure[N - 1];

const letters = W.letters.map((l, k) => ({ ...l, penKern: +penKern[k].toFixed(4) }));
const ink0 = Math.min(...letters.map(l => l.penKern + l.inkRel[0]));
const ink1 = Math.max(...letters.map(l => l.penKern + l.inkRel[2]));
const inkW = ink1 - ink0;
const boundary = letters[4].penKern - ink0;

const out = {
  _what: '含 kerning 的笔位（units, y 向下, 基线=0），由 Chrome 排版 7 个前缀串反推',
  _method: `font 600 ${F}px, letter-spacing −0.02em, font-optical-sizing:none, font-kerning:normal`,
  measuredPrefixPxAt1000: P, px2u, lsPx: LS, F,
  penPure, penKern: letters.map(l => l.penKern), pairs, totalKern: +totalKern.toFixed(4),
  boxWidth: +(W.boxWidth + totalKern).toFixed(4),
  inkWidth: +inkW.toFixed(4), inkX0: +ink0.toFixed(4),
  boundaryFromInkLeft: +boundary.toFixed(4),
  boundaryPct: +(100 * boundary / inkW).toFixed(4),
  inkWidthEm: +(inkW / W.upm).toFixed(6),
  boxWidthEm: +((W.boxWidth + totalKern) / W.upm).toFixed(6),
};
writeFileSync(join(HERE, '.v2-wordmark.kern.json'), JSON.stringify(out, null, 1));

console.log('前 8 个前缀宽度（1000px 下，px）：' + P.map(v => v.toFixed(2)).join(' '));
console.log('kerning 逐对：' + pairs.map(p => `${p.pair} ${p.kern >= 0 ? '+' : ''}${p.kern}`).join('  '));
console.log(`kerning 合计 ${totalKern.toFixed(2)}u（${(totalKern / W.upm).toFixed(4)}em）`);
console.log(`含 kern 盒宽 ${out.boxWidth}u = ${out.boxWidthEm}em ｜ 墨宽 ${out.inkWidth}u = ${out.inkWidthEm}em`);
console.log(`墨左 ${out.inkX0}u ｜ 分界 ${out.boundaryPct}%（08 §3 的 50.045% 是纯进宽口径）`);
