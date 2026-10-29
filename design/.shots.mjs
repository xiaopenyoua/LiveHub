// 截图 + 控制台错误检查（1920×1080 精确视口）
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE = 'file:///Volumes/ssd/projects-github/LiveHub/design/index.html?canvas=1';
const OUT = '/Volumes/ssd/projects-github/LiveHub/design/screenshots';
mkdirSync(OUT, { recursive: true });

const proc = spawn(CHROME, ['--headless=new','--no-sandbox','--disable-gpu','--hide-scrollbars',
  '--remote-debugging-port=9335','--force-device-scale-factor=1','--window-size=1920,1080',
  BASE + '&screen=home'], { stdio:'ignore' });
const wait = ms => new Promise(r=>setTimeout(r,ms));

async function main(){
  let target=null;
  for(let i=0;i<40;i++){ await wait(300);
    try{ const l=await (await fetch('http://127.0.0.1:9335/json')).json();
      target=l.find(t=>t.type==='page'&&t.url.includes('index.html')); if(target)break; }catch{} }
  if(!target) throw new Error('no target');

  const ws=new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(r=>ws.addEventListener('open',r,{once:true}));
  let id=0; const pending=new Map(); const errs=[];
  ws.addEventListener('message',e=>{
    const m=JSON.parse(e.data);
    if(m.method==='Runtime.consoleAPICalled' && m.params.type==='error') errs.push(m.params.args.map(a=>a.value||a.description).join(' '));
    if(m.method==='Runtime.exceptionThrown') errs.push('EXC '+(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text));
    if(pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id);}
  });
  const send=(method,params={})=>new Promise(res=>{const i=++id;pending.set(i,res);ws.send(JSON.stringify({id:i,method,params}));});
  const ev=async expr=>{const r=await send('Runtime.evaluate',{expression:expr,returnByValue:true,awaitPromise:true});
    if(r.result?.exceptionDetails) return 'ERR '+r.result.exceptionDetails.text; return r.result?.result?.value;};

  await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width:1920, height:1080, deviceScaleFactor:1, mobile:false });
  await wait(1800);
  await ev('document.fonts.ready.then(()=>true)');

  const shots = [
    ['01-home','home',null],
    ['02-browse','browse',null],
    ['03-follow','follow',null],
    ['04-search','search',null],
    ['05-player','player',null],
    ['06-settings','settings',null],
    ['07-channel-osd','player','osd'],
    ['08-exit-dialog','home','exit'],
  ];

  for(const [name, screen, mode] of shots){
    await ev(`(()=>{
      document.getElementById('exitDialog')?.classList.remove('is-open');
      showScreen('${screen}',{force:true});
    })()`);
    await wait(260);
    if(mode==='osd'){ await ev(`loadRoom(301); (()=>{document.getElementById('osd').classList.add('is-open'); const h=document.getElementById('osdHint'); if(h) h.classList.add('is-open');})()`); }
    if(mode==='exit'){ await ev(`(()=>{document.getElementById('exitDialog').classList.add('is-open');})()`); }
    await wait(650);
    const r = await send('Page.captureScreenshot', { format:'png', captureBeyondViewport:false });
    writeFileSync(`${OUT}/${name}.png`, Buffer.from(r.result.data,'base64'));
    console.log('shot', name);
  }

  console.log('console errors:', errs.length ? JSON.stringify(errs.slice(0,8)) : 'none');
  ws.close(); proc.kill(); process.exit(0);
}
main().catch(e=>{console.error(e);proc.kill();process.exit(1);});
