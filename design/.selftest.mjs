// 用 CDP 验证遥控器键位真的能操作界面（不是只有静态截图）
import { spawn } from 'node:child_process';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const URL = 'file:///Volumes/ssd/projects-github/LiveHub/design/index.html?canvas=1&screen=home';

const proc = spawn(CHROME, [
  '--headless=new','--no-sandbox','--disable-gpu','--hide-scrollbars',
  '--remote-debugging-port=9333','--window-size=1920,1080', URL
], { stdio:'ignore' });

const wait = ms => new Promise(r=>setTimeout(r,ms));

async function main(){
  let target = null;
  for(let i=0;i<40;i++){
    await wait(300);
    try{
      const list = await (await fetch('http://127.0.0.1:9333/json')).json();
      target = list.find(t=>t.type==='page' && t.url.includes('index.html'));
      if(target) break;
    }catch{}
  }
  if(!target) throw new Error('no target');

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(r=>ws.addEventListener('open', r, {once:true}));
  let id = 0; const pending = new Map();
  ws.addEventListener('message', e=>{
    const m = JSON.parse(e.data);
    if(pending.has(m.id)){ pending.get(m.id)(m); pending.delete(m.id); }
  });
  const send = (method, params={}) => new Promise(res=>{
    const i = ++id; pending.set(i, res); ws.send(JSON.stringify({id:i, method, params}));
  });
  const evalJS = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue:true, awaitPromise:true });
    if(r.result?.exceptionDetails) return 'ERR ' + r.result.exceptionDetails.text;
    return r.result?.result?.value;
  };

  await wait(1500);

  const press = k => evalJS(`document.dispatchEvent(new KeyboardEvent('keydown',{key:${JSON.stringify(k)},bubbles:true}))`);

  // 焦点描述：优先台号，其次文字，最后 class —— 三卡片同号时也不会误判
  const describe = () => evalJS(`(()=>{
      const s=document.querySelector('.screen.is-active').dataset.screen;
      const f=document.querySelector('.is-focused');
      const dlg=document.getElementById('exitDialog');
      if(dlg && dlg.classList.contains('is-open')) return s+'  |  退出确认弹层已打开';
      let t='—';
      if(s==='player'){
        const osd=document.getElementById('osd');
        t=[document.getElementById('pvNo').textContent, osd.classList.contains('is-open')?'换台条':'控制条'].join(' · ');
      } else if(f){
        const txt = f.textContent.trim().replace(/\\s+/g,' ');
        t = (f.dataset.play ? '台号 '+f.dataset.play+' · ' : '') + (txt? txt.slice(0,16) : f.className.split(' ')[0]);
      }
      return s+'  |  '+t;
  })()`);

  const rows = [];
  async function step(label, ...keys){
    for(const k of keys){ await press(k); await wait(200); }
    rows.push([label, await describe()]);
  }

  // 走一遍真实使用路径：首页 → 换卡 → 进直播间 → 换台 → 换平台 → 数字直达 → 回首页 → 退出
  await step('初始（首页主图按钮）');
  await step('→ 第一张卡', 'ArrowRight');
  await step('→ 下一张卡', 'ArrowRight');
  await step('→ 再下一张', 'ArrowRight');
  await step('OK 进直播间', 'Enter');
  await step('↑ 呼出换台条（上一台）', 'ArrowUp');
  await step('↑ 再上一台', 'ArrowUp');
  await step('← 换平台', 'ArrowLeft');
  await step('OK 关闭换台条', 'Enter');
  await step('数字 2 0 3 直达', '2','0','3');
  await step('Esc 收起控制条', 'Escape');
  await step('Esc 回首页', 'Escape');
  await step('Esc 弹退出确认', 'Escape');

  // 焦点困住检测：每次按键用一次性 token 判断焦点是否真的换了节点
  await evalJS(`document.getElementById('exitDialog').classList.remove('is-open')`);
  const stuckDetail = [];
  let total = 0;
  for(const s of ['home','browse','follow','search','settings']){
    await evalJS(`showScreen('${s}',{force:true})`);
    await wait(320);
    for(const k of ['ArrowRight','ArrowDown','ArrowLeft','ArrowUp','ArrowUp','ArrowDown','ArrowLeft','ArrowRight']){
      const tok = 't' + Math.random().toString(36).slice(2);
      await evalJS(`(()=>{const f=document.querySelector('.is-focused'); if(f) f.dataset.fk='${tok}';})()`);
      await press(k); await wait(160);
      const same = await evalJS(`(()=>{const f=document.querySelector('.is-focused'); return !!f && f.dataset.fk==='${tok}';})()`);
      total++; if(same){ stuckDetail.push(s+':'+k); }
    }
  }

  // 每个界面的可聚焦元素数量（防止某一屏没有入口）
  const counts = await evalJS(`(()=>{
    const o={};
    for(const s of ['home','browse','follow','search','settings']){
      showScreen(s,{force:true});
      o[s]=document.querySelectorAll('.screen.is-active .focusable').length;
    }
    showScreen('home',{force:true});
    return o;
  })()`);

  console.log('\n步骤'.padEnd(28), '状态');
  console.log('─'.repeat(76));
  for(const [a,b] of rows) console.log(String(a).padEnd(28), b);
  console.log('─'.repeat(76));
  console.log('可聚焦元素：', JSON.stringify(counts));
  console.log('焦点原地不动：'+stuckDetail.length+' / '+total+'  '+JSON.stringify(stuckDetail));

  ws.close(); proc.kill();
  process.exit(0);
}
main().catch(e=>{ console.error(e); proc.kill(); process.exit(1); });
