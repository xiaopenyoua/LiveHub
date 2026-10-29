// 图标取证截图：五档 1:1 像素图 + 整页 + 单色模拟特写
// 用法：node design/.icon-shots.mjs
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, 'brand', 'shots');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE = 'file://' + join(HERE, 'brand', 'icon-preview.html');
const PORT = 9477;
mkdirSync(OUT, { recursive: true });

const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
  '--force-color-profile=srgb', '--force-device-scale-factor=1',
  `--remote-debugging-port=${PORT}`, '--window-size=1920,1080', BASE], { stdio: 'ignore' });
const wait = ms => new Promise(r => setTimeout(r, ms));

async function main() {
  let target = null;
  for (let i = 0; i < 50; i++) {
    await wait(300);
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
      target = list.find(t => t.type === 'page' && t.url.includes('icon-preview.html'));
      if (target) break;
    } catch { }
  }
  if (!target) throw new Error('连不上 Chrome');

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(r => ws.addEventListener('open', r, { once: true }));
  let id = 0; const pending = new Map(); const errs = [];
  ws.addEventListener('message', e => {
    const m = JSON.parse(e.data);
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') errs.push(m.params.args.map(a => a.value || a.description).join(' '));
    if (m.method === 'Runtime.exceptionThrown') errs.push('EXC ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
    if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  });
  const send = (method, params = {}) => new Promise(res => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
  const ev = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) return 'ERR ' + (r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
    return r.result?.result?.value;
  };

  await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1920, height: 1080, deviceScaleFactor: 1, mobile: false });
  await wait(1200);
  await ev('document.fonts.ready.then(()=>true)');
  await ev('Promise.all([...document.images].map(i=>i.decode().catch(()=>0)))');

  // 字体是否真的加载（file:// 下 @font-face 可能被拦，必须实测而不是假设）
  const fontOk = await ev(`(async()=>{
    await document.fonts.ready;
    const loaded=[...document.fonts].map(f=>f.family+':'+f.status);
    const probe=(fam)=>{const s=document.createElement('span');s.style.cssText='position:absolute;font-size:40px;font-weight:600;letter-spacing:0';s.textContent='LiveHub 192';s.style.fontFamily=fam;document.body.appendChild(s);const w=s.getBoundingClientRect().width;s.remove();return +w.toFixed(2)};
    return {loaded, inter:probe('Inter'), fallback:probe('system-ui'), check:document.fonts.check('600 40px Inter')};
  })()`);

  const rect = async sel => ev(`(()=>{const e=document.querySelector(${JSON.stringify(sel)}); if(!e) return null; const r=e.getBoundingClientRect(); return {x:r.x,y:r.y,w:r.width,h:r.height};})()`);

  const shots = [];
  const shoot = async (file, r, label) => {
    if (!r) throw new Error('找不到元素：' + label);
    const clip = { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.w), height: Math.round(r.h), scale: 1 };
    const s = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false, clip });
    writeFileSync(join(OUT, file), Buffer.from(s.result.data, 'base64'));
    shots.push([file, `${clip.width}×${clip.height}`, label]);
  };

  // 五档里挑 48 / 96 / 192 三张 1:1（不是放大，是截取真实像素）
  for (const P of [48, 96, 192]) {
    const idx = { 48: 1, 96: 3, 192: 5 }[P];
    await shoot(`icon-${P}.png`, await rect(`.specimen .cell:nth-child(${idx}) .plate`), `${P}px 1:1`);
  }
  await shoot('icon-theme.png', await rect('.theme'), '单色 / 主题图标模拟区');

  // 整页
  const full = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  writeFileSync(join(OUT, 'icon-preview-1920.png'), Buffer.from(full.result.data, 'base64'));
  shots.push(['icon-preview-1920.png', '1920×1080', '整页']);

  // 版面实测：图标是不是真的 1:1、五档是不是坐在同一条基线上、块与块有没有重叠
  const layout = await ev(`(()=>{
    const R=e=>e.getBoundingClientRect();
    const plates=[...document.querySelectorAll('.specimen .cell .plate')].map(e=>{const r=R(e);return {w:Math.round(r.width),h:Math.round(r.height),bottom:Math.round(r.bottom),left:Math.round(r.left)}});
    const q=s=>{const e=document.querySelector(s);return e?R(e):null};
    const box=(r,sel)=>({sel,top:Math.round(r.top),bottom:Math.round(r.bottom),left:Math.round(r.left),right:Math.round(r.right)});
    const blocks=['.hdr','.rowA','.rowB','.foot'].map(s=>box(q(s),s));
    const overlap=(a,b)=>!(a.right<=b.left||b.right<=a.left||a.bottom<=b.top||b.bottom<=a.top);
    const ov=[];for(let i=0;i<blocks.length;i++)for(let j=i+1;j<blocks.length;j++)if(overlap(blocks[i],blocks[j]))ov.push(blocks[i].sel+'×'+blocks[j].sel);
    return {plates, baselineSame:new Set(plates.map(p=>p.bottom)).size===1, baselineY:plates[0].bottom, oneToOne:plates.every((p,i)=>p.w===[48,72,96,144,192][i]&&p.h===[48,72,96,144,192][i]),
      blocks, overlaps:ov, bodyH:document.body.scrollHeight, bodyW:document.body.scrollWidth};
  })()`);

  // 版面溢出检查：任何元素超出 1920×1080 都是版面事故
  const overflow = await ev(`(()=>{
    const b=[...document.querySelectorAll('.stage *')].map(e=>({t:(e.className||e.tagName).toString().slice(0,28),r:e.getBoundingClientRect()}))
      .filter(o=>o.r.right>1920.5||o.r.bottom>1080.5||o.r.left<-0.5||o.r.top<-0.5);
    return {count:b.length, sample:b.slice(0,6).map(o=>o.t+':'+[o.r.left,o.r.top,o.r.right,o.r.bottom].map(n=>Math.round(n)).join(','))};
  })()`);

  console.log('字体实测：' + JSON.stringify(fontOk));
  console.log('\n文件'.padEnd(26) + '像素'.padEnd(12) + '内容');
  console.log('─'.repeat(74));
  for (const [f, s, l] of shots) console.log(f.padEnd(26) + s.padEnd(12) + l);
  console.log('─'.repeat(74));
  console.log('越界元素：' + JSON.stringify(overflow));
  console.log('\n版面实测：五档图标 ' + JSON.stringify(layout.plates.map(p=>p.w+'×'+p.h)) +
    ' → 1:1 = ' + (layout.oneToOne ? '是 ✓' : '否 ✗'));
  console.log('五档底边同一条基线 = ' + (layout.baselineSame ? '是 ✓ y=' + layout.baselineY : '否 ✗ ' + layout.plates.map(p=>p.bottom).join('/')));
  console.log('四块版面矩形：' + layout.blocks.map(b=>`${b.sel}[${b.top}→${b.bottom}]`).join(' '));
  console.log('块间重叠：' + (layout.overlaps.length ? layout.overlaps.join(',') : '无 ✓') +
    '　文档尺寸：' + layout.bodyW + '×' + layout.bodyH);
  console.log('console errors: ' + (errs.length ? JSON.stringify(errs.slice(0, 8)) : 'none'));

  ws.close(); proc.kill(); process.exit(0);
}
main().catch(e => { console.error(e.message); proc.kill(); process.exit(1); });
