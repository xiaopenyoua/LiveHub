// 诊断：找出越界 / 贴边的元素
import { spawn } from 'node:child_process';
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const URL = 'file:///Volumes/ssd/projects-github/LiveHub/design/index.html?canvas=1&screen=home';
const proc = spawn(CHROME, ['--headless=new','--no-sandbox','--disable-gpu','--hide-scrollbars',
  '--remote-debugging-port=9334','--window-size=1920,1080', URL], { stdio:'ignore' });
const wait = ms => new Promise(r=>setTimeout(r,ms));

async function main(){
  let target=null;
  for(let i=0;i<40;i++){ await wait(300);
    try{ const l=await (await fetch('http://127.0.0.1:9334/json')).json();
      target=l.find(t=>t.type==='page'&&t.url.includes('index.html')); if(target)break; }catch{} }
  if(!target) throw new Error('no target');
  const ws=new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(r=>ws.addEventListener('open',r,{once:true}));
  let id=0; const pending=new Map();
  ws.addEventListener('message',e=>{const m=JSON.parse(e.data); if(pending.has(m.id)){pending.get(m.id)(m);pending.delete(m.id);}});
  const send=(method,params={})=>new Promise(res=>{const i=++id;pending.set(i,res);ws.send(JSON.stringify({id:i,method,params}));});
  const ev=async expr=>{const r=await send('Runtime.evaluate',{expression:expr,returnByValue:true,awaitPromise:true});
    if(r.result?.exceptionDetails) return 'ERR '+r.result.exceptionDetails.text; return r.result?.result?.value;};
  await wait(1600);

  for(const s of ['home','browse','follow','search','player','settings']){
    await ev(`showScreen('${s}',{force:true})`); await wait(350);
    const out = await ev(`(()=>{
      const W=innerWidth,H=innerHeight,bad=[];
      const walk=(root)=>{
        for(const el of root.querySelectorAll('*')){
          if(el.closest('.chrome,.hintbar')) continue;
          const hasText=[...el.childNodes].some(n=>n.nodeType===3&&n.textContent.trim());
          const r=el.getBoundingClientRect();
          if(r.width<2||r.height<2) continue;
          if(!hasText) continue;
          const right = W - r.right, left = r.left, bottom = H - r.bottom;
          if(right < 16 || left < 0 || bottom < 0 || r.top < 0)
            bad.push({t:el.textContent.trim().slice(0,18),cls:el.className,r:[Math.round(r.left),Math.round(r.top),Math.round(r.right),Math.round(r.bottom)],right:Math.round(right),left:Math.round(left),bottom:Math.round(bottom)});
        }
      };
      walk(document.querySelector('.screen.is-active'));
      return bad;
    })()`);
    console.log('== '+s+' ==');
    if(!Array.isArray(out)) console.log(out);
    else if(!out.length) console.log('  ok');
    else out.forEach(o=>console.log('  ', JSON.stringify(o)));
  }

  // 字体实际渲染
  const f = await ev(`getComputedStyle(document.body).fontFamily`);
  console.log('body font:', f);
  ws.close(); proc.kill(); process.exit(0);
}
main().catch(e=>{console.error(e);proc.kill();process.exit(1);});
