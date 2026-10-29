// 量 Inter 的字体度量，用来把「baseline 583」翻译成 CSS 的 top 值。
// 只读设计稿，不写任何文件。
// 用法：node design/.splash-probe-font.mjs
import { spawn } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE = 'file://' + join(HERE, 'index.html');
const PORT = 9491;
const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
  '--force-color-profile=srgb', '--force-device-scale-factor=1',
  `--remote-debugging-port=${PORT}`, '--window-size=1280,800', BASE], { stdio: 'ignore' });
const wait = ms => new Promise(r => setTimeout(r, ms));

async function main() {
  let target = null;
  for (let i = 0; i < 40; i++) {
    await wait(300);
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
      target = list.find(t => t.type === 'page' && t.url.includes('index.html'));
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
  await wait(800);
  const out = await ev(`(async()=>{
    await document.fonts.ready;
    const mk=(fs,w,ls)=>{const c=document.createElement('canvas').getContext('2d');c.font=(w||600)+' '+fs+'px Inter';const m=c.measureText('LiveHub');
      return {fs,w,ls,ok:document.fonts.check(w+' '+fs+'px Inter'),
        fba:+m.fontBoundingBoxAscent.toFixed(3), fbd:+m.fontBoundingBoxDescent.toFixed(3),
        cap:+m.actualBoundingBoxAscent.toFixed(3), adv:+m.width.toFixed(3)};};
    const list=[120,76,34,30,26,24,22].map(fs=>mk(fs,600));
    list.push(mk(22,400),mk(24,400),mk(26,500),mk(30,500),mk(34,600));
    // 真实 DOM：line-height:1 时，行盒顶到基线的距离 = (fs-(A+D))/2 + A
    const dom=(fs,w,ls,text)=>{const d=document.createElement('div');
      d.style.cssText='position:absolute;left:0;top:0;font-family:Inter;font-size:'+fs+'px;font-weight:'+w+';letter-spacing:'+ls+'px;line-height:1;white-space:pre';
      d.textContent=text;document.body.appendChild(d);
      const r=d.getBoundingClientRect();const w2=+r.width.toFixed(3);const h=+r.height.toFixed(3);
      d.remove();return {fs,w,ls,text,w:w2,h};};
    const W=(fs,w,ls,t)=>dom(fs,w,ls,t);
    return {
      canvas:list,
      wordmark120:W(120,600,-2.4,'LiveHub'),
      wordmark120_nols:W(120,600,0,'LiveHub'),
      brand30:W(30,600,-0.6,'LiveHub'),
      h1:W(76,600,-1.9,'LPL 夏季赛 决赛日·第三局'),
      row22:W(22,600,0,'超时降级'),
      row22_400:W(22,400,0,'超时降级'),
      fail34:W(34,600,0,'四家平台都没连上'),
      fail24:W(24,400,0,'网络在，但四家都没有响应。稍等片刻再试一次。'),
      btn26:W(26,500,0,'重试'),
      cjk22:W(22,400,0,'哔哩'),
    };
  })()`);
  console.log(JSON.stringify(out, null, 1));
  ws.close(); proc.kill(); process.exit(0);
}
main().catch(e => { console.error(e.message); proc.kill(); process.exit(1); });
