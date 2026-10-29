// 校准 v2：用「整行覆盖率之和 / 上一行之和」求子像素边缘，比取单列稳。
import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG } from './.png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FILE = 'file://' + join(HERE, 'splash.html');
const PORT = 9494;
const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
  '--disable-lcd-text', '--force-color-profile=srgb', '--force-device-scale-factor=1',
  `--remote-debugging-port=${PORT}`, '--window-size=1920,1080', FILE + '?canvas=1&still=1'], { stdio: 'ignore' });
const wait = ms => new Promise(r => setTimeout(r, ms));

async function main() {
  let target = null;
  for (let i = 0; i < 50; i++) {
    await wait(300);
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
      target = list.find(t => t.type === 'page' && t.url.includes('splash.html'));
      if (target) break;
    } catch { }
  }
  if (!target) throw new Error('连不上 Chrome');
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(r => ws.addEventListener('open', r, { once: true }));
  let id = 0; const pending = new Map();
  ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } });
  const send = (method, params = {}) => new Promise(res => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
  const ev = async expr => (await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result?.result?.value;
  await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1920, height: 1080, deviceScaleFactor: 1, mobile: false });
  await wait(600);
  await ev('document.fonts.ready.then(()=>1)');

  // 只画 "L"：底边是一条通宽的实心横干，适合积分求边
  const SIZES = [120, 76, 34, 30, 26, 24, 22, 18];
  const tops = {};
  let y = 20;
  await ev(`(()=>{const d=document.createElement('div');d.id='calib';
    d.style.cssText='position:absolute;left:0;top:0;width:900px;height:1080px;background:#000;z-index:9999';
    document.getElementById('stage').appendChild(d);return 1})()`);
  for (const fs of SIZES) {
    tops[fs] = y;
    await ev(`(()=>{const s=document.createElement('div');
      s.style.cssText='position:absolute;left:20px;top:${y}px;font:600 ${fs}px/1 "Inter";color:#fff;white-space:pre';
      s.textContent='L';document.getElementById('calib').appendChild(s);return 1})()`);
    y += Math.round(fs * 1.55) + 24;
  }
  await wait(300);
  const s = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false, clip: { x: 0, y: 0, width: 900, height: 1080, scale: 1 } });
  const img = decodePNG(Buffer.from(s.result.data, 'base64'));
  const ch = img.ch;
  const rowSum = yy => { let t = 0; for (let x = 0; x < 400; x++) t += img.data[(yy * img.w + x) * ch]; return t; };
  const out = {};
  for (const fs of SIZES) {
    const t = tops[fs];
    const rows = [];
    for (let yy = t - 3; yy < t + Math.ceil(fs * 1.7); yy++) rows.push([yy, rowSum(yy)]);
    const inked = rows.filter(r => r[1] > 0);
    const first = inked[0], last = inked[inked.length - 1];
    // 取「最宽的一行」当作满覆盖宽度
    const full = Math.max(...rows.map(r => r[1]));
    const topEdge = first[0] + (1 - first[1] / full);
    const botEdge = last[0] + last[1] / full;
    out[fs] = {
      top: t, fullRowPx: full / 255,
      capTop: +topEdge.toFixed(2), baseline: +botEdge.toFixed(2),
      off: +(botEdge - t).toFixed(3), ratio: +((botEdge - t) / fs).toFixed(5),
      capHeight: +(botEdge - topEdge).toFixed(2), capRatio: +((botEdge - topEdge) / fs).toFixed(5),
    };
  }
  console.log(JSON.stringify(out, null, 1));
  ws.close(); proc.kill(); process.exit(0);
}
main().catch(e => { console.error(e.message); proc.kill(); process.exit(1); });
