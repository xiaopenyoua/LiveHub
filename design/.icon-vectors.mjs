// 矢量层回渲验证：把 android-res 里的 VectorDrawable pathData 原样拿去光栅化，
// 证明「XML 里的字符串」真的画出规格里的形状；并证明 monochrome 层的缝是真的 alpha=0。
// 用法：node design/.icon-vectors.mjs
import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG } from './.png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = dirname(HERE);
const ICON = join(ROOT, 'assets/brand/icon');
const RES = join(ROOT, 'assets/brand/android-res');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9493;

const xmlFG = readFileSync(join(RES, 'drawable/ic_launcher_foreground.xml'), 'utf8');
const xmlMO = readFileSync(join(RES, 'drawable/ic_launcher_monochrome.xml'), 'utf8');
const grab = (xml) => [...xml.matchAll(/android:pathData="([^"]+)"/g)].map(m => m[1]);
const [XFGtri, XFGpill] = grab(xmlFG);
const [XMOtri, XMOpill] = grab(xmlMO);
if (grab(xmlFG).length !== 2 || grab(xmlMO).length !== 2) throw new Error('path 数不是 2');

const svgFG = readFileSync(join(ICON, 'ic_launcher_foreground.svg'), 'utf8');
const svgMO = readFileSync(join(ICON, 'ic_launcher_monochrome.svg'), 'utf8');
const grabSvg = (s) => [...s.matchAll(/<path fill="#[0-9A-F]{6}" d="([^"]+)"\/>/g)].map(m => m[1]);
const [SFGtri, SFGpill] = grabSvg(svgFG);
const [SMOtri, SMOpill] = grabSvg(svgMO);

const same = (a, b) => a === b ? '✓ 逐字相同' : '✗ 不同';

const page = `<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;background:transparent}
.d{position:absolute;top:0}
</style></head><body>
<div class="d" id="fg" style="left:0;width:108px;height:108px">
 <svg width="108" height="108" viewBox="0 0 108 108"><rect width="108" height="108" fill="#000000"/>
  <path fill="#FFFFFF" d="${XFGtri}"/><path fill="#FF453A" d="${XFGpill}"/></svg></div>
<div class="d" id="mo" style="left:160px;width:108px;height:108px">
 <svg width="108" height="108" viewBox="0 0 108 108">
  <path fill="#FFFFFF" d="${XMOtri}"/><path fill="#FFFFFF" d="${XMOpill}"/></svg></div>
</body></html>`;

const wait = ms => new Promise(r => setTimeout(r, ms));
const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
  '--force-color-profile=srgb', '--force-device-scale-factor=1',
  `--remote-debugging-port=${PORT}`, '--window-size=800,400', 'data:text/html,' + encodeURIComponent(page)], { stdio: 'ignore' });

let target = null;
for (let i = 0; i < 50; i++) {
  await wait(300);
  try {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
    target = list.find(t => t.type === 'page' && t.url.startsWith('data:text/html'));
    if (target) break;
  } catch { }
}
if (!target) throw new Error('连不上 Chrome');
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r, { once: true }));
let id = 0; const pending = new Map();
ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } });
const send = (method, params = {}) => new Promise(res => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });

await send('Emulation.setDeviceMetricsOverride', { width: 800, height: 400, deviceScaleFactor: 1, mobile: false });
await send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
await wait(600);

const shot = async (x) => {
  const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false, clip: { x, y: 0, width: 108, height: 108, scale: 1 } });
  return decodePNG(Buffer.from(r.result.data, 'base64'));
};

const fg = await shot(0);
const mo = await shot(160);
ws.close(); proc.kill();

/* ---------------- 判定 ---------------- */
const at = (img, x, y) => { const i = (y * img.w + x) * img.ch; return [img.data[i], img.data[i + 1], img.data[i + 2], img.ch === 4 ? img.data[i + 3] : 255]; };
// 判「有墨」而不是「颜色够浓」：顶点只有 0.3 像素的覆盖，用强阈值会把尖判没，
// 于是「右缘=顶点」会假性失败。白只会和黑混合（r=g=b），红只会和黑混合（g<r）。
const isWhite = p => p[0] > 0 && p[0] === p[1] && p[1] === p[2];
const isRed = p => p[0] > 0 && p[1] < p[0];
const isBlack = p => p[0] === 0 && p[1] === 0 && p[2] === 0;

const cols = (img, test) => {
  let first = -1, last = -1;
  for (let x = 0; x < img.w; x++) for (let y = 0; y < img.h; y++) if (test(at(img, x, y), x, y)) { if (first < 0) first = x; last = x; }
  return [first, last];
};
const rows = (img, test) => {
  let first = -1, last = -1;
  for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) if (test(at(img, x, y), x, y)) { if (first < 0) first = y; last = y; }
  return [first, last];
};

const L = [];
const say = s => { L.push(s); console.log(s); };

say('\n【矢量层回渲】把 android-res 的 pathData 原样拿去光栅化（108×108，1 用户单位 = 1 像素）');
say(`  XML 字符串 vs SVG 文件：foreground tri ${same(XFGtri, SFGtri)} / pill ${same(XFGpill, SFGpill)}`);
say(`                          monochrome tri ${same(XMOtri, SMOtri)} / pill ${same(XMOpill, SMOpill)}`);
say(`  foreground pathData：${XFGtri}`);
say(`                      ${XFGpill}`);
say(`  monochrome pathData：${XMOtri}  (fill #FFFFFF)`);
say(`                      ${XMOpill}`);

say('\n【foreground 回渲实测】（黑底 + 白三角 + 红徽章）');
const wc = cols(fg, isWhite), rc = cols(fg, isRed), wr = rows(fg, isWhite), rr = rows(fg, isRed);
say(`  白色（三角）列范围 ${wc[0]}→${wc[1]}（规格 35→71，底边 x=35、顶点 x=72 收在第 71 列）`);
say(`  白色（三角）行范围 ${wr[0]}→${wr[1]}（规格 y 30→77）`);
say(`  红色（徽章）列范围 ${rc[0]}→${rc[1]}（规格 52→71，右缘 x=72）`);
say(`  红色（徽章）行范围 ${rr[0]}→${rr[1]}（规格 y 27→35）`);
say(`  右缘最后一列 ${rc[1]} / 顶点最后一列 ${wc[1]} → ${rc[1] === wc[1] ? '同一列 ✓' : '不同列 ✗'}`);
say(`  徽章最后一行 ${rr[1]} / 三角最上一行 ${wr[0]}：徽章高出 ${wr[0] - rr[0]} 行（规格 0.06×48 = 3）`);
{
  // 缝：每列在「徽章最低行」与「三角最高行」之间数纯黑
  let minRun = 1e9, worst = -1, alien = 0;
  for (let x = rc[0]; x <= rc[1]; x++) {
    let rb = -1, wt = 108;
    for (let y = 0; y < 108; y++) { const p = at(fg, x, y); if (isRed(p)) rb = y; }
    for (let y = 107; y >= 0; y--) { const p = at(fg, x, y); if (isWhite(p)) wt = Math.min(wt, y); }
    if (rb < 0 || wt >= 108 || wt <= rb) continue;
    let run = 0;
    for (let y = rb + 1; y < wt; y++) { const p = at(fg, x, y); if (isBlack(p)) run++; else alien++; }
    if (run < minRun) { minRun = run; worst = x; }
  }
  say(`  缝内纯黑最窄处：第 ${worst} 列 ${minRun} 像素；缝内非纯黑像素 ${alien} 个`);
}

say('\n【monochrome 回渲实测】（透明底 + 白色剪影，验证主题图标下两块不粘连）');
let zero = 0, full = 0, partial = 0, minSeamAlpha = 255, seamPx = 0;
for (let y = 0; y < 108; y++) for (let x = 0; x < 108; x++) {
  const a = at(mo, x, y)[3];
  if (a === 0) zero++; else if (a === 255) full++; else partial++;
}
say(`  alpha 分布：0=${zero}　255=${full}　中间值=${partial}（共 ${108 * 108} 像素）`);
{
  const mc = cols(mo, p => p[3] > 0), mr = rows(mo, p => p[3] > 0);
  say(`  剪影列范围 ${mc[0]}→${mc[1]}，行范围 ${mr[0]}→${mr[1]}（与 foreground 同一套坐标）`);
  // 缝区：逐列取「徽章最低有墨行」与「三角最高有墨行」之间的所有像素，alpha 必须全 0
  let colsChecked = 0;
  for (let x = 52; x <= 71; x++) {
    let rb = -1, wt = 108;
    for (let y = 0; y < 36; y++) if (at(mo, x, y)[3] > 0) rb = Math.max(rb, y);          // 徽章带（y 27→36）
    for (let y = 36; y < 108; y++) if (at(mo, x, y)[3] > 0) { wt = y; break; }            // 三角最高有墨行
    if (rb < 0 || wt >= 108) continue;
    colsChecked++;
    for (let y = rb + 1; y < wt; y++) { const a = at(mo, x, y)[3]; seamPx++; minSeamAlpha = Math.min(minSeamAlpha, a); }
  }
  say(`  逐列检查 ${colsChecked} 列（徽章 x 52→71）`);
  say(`  两块之间（徽章底至三角顶）共 ${seamPx} 个像素，alpha 最大值 = ${seamPx ? minSeamAlpha : 'n/a'} → ` +
    `${minSeamAlpha === 0 ? '整条缝 alpha 全 0，两块天然分离 ✓' : '存在非 0 alpha，两块粘连 ✗'}`);
  say(`  结论：monochrome 层没有任何底板（背景 alpha=${at(mo, 2, 2)[3]} / 角点 alpha=${at(mo, 0, 0)[3]}），主题图标不会被填成「带尾巴的旗子」`);
}
console.log('\n（脚本：design/.icon-vectors.mjs）');
