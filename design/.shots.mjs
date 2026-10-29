// 批量重截交付截图：1920×1080、canvas 模式、各屏默认焦点。
// 用法：node design/.shots.mjs
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, 'screenshots');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const BASE = 'file://' + join(HERE, 'index.html') + '?canvas=1';
const PORT = 9371;

const proc = spawn(CHROME, ['--headless=new','--no-sandbox','--disable-gpu','--hide-scrollbars',
  `--remote-debugging-port=${PORT}`,'--force-device-scale-factor=1','--window-size=1920,1080',
  BASE + '&screen=home'], { stdio: 'ignore' });
const wait = ms => new Promise(r => setTimeout(r, ms));

// 每张图一段设定脚本 + 一个文件名。焦点只对「弹层」和「左栏」两种特殊态做覆盖，
// 其余走各屏 DEFAULT_FOCUS，拍到的就是用户打开时真正看到的那一帧。
const SHOTS = [
  ['01-home',          `showScreen('home')`],
  ['02-browse',        `showScreen('browse')`],
  ['03-follow',        `showScreen('follow')`],
  // 搜索页空查询不显示结果（那是假状态），所以这里先输一个真查询，拍到的才是"搜到了"的一帧
  ['04-search',        `showScreen('search'); setQuery('lp')`],
  ['05-player',        `showScreen('player')`],
  ['06-settings',      `showScreen('settings')`],
  ['07-channel-osd',   `showScreen('player'); playerState='osd'; renderPlayerState()`],
  ['08-exit-dialog',   `showScreen('home'); openExit()`],
  // 焦点放到分组条：证明分组和条目是同一块玻璃，不是两套语言。
  // 左栏已改为只读、没有可聚焦元素，这一张不再拍左栏。
  ['09-settings-nav',  `showScreen('settings'); setFocus($('#sgroups .sgroup'), false)`],
  // 房间号直达：一块键盘里直接打 660000（没有"数字模式"可切），结果区给出「按搜索直达」那一句
  ['10-search-rid',    `showScreen('search'); setQuery('660000'); setFocus($('[data-key="6"]'), false)`],
];

async function main() {
  let target = null;
  for (let i = 0; i < 40; i++) {
    await wait(300);
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
      target = list.find(t => t.type === 'page' && t.url.includes('index.html'));
      if (target) break;
    } catch {}
  }
  if (!target) throw new Error('连不上 Chrome');

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(r => ws.addEventListener('open', r, { once: true }));
  let id = 0; const pending = new Map();
  // 截图顺带当 console 闸门：任何 error 都会在最后一行报出来
  const errs = [];
  ws.addEventListener('message', e => {
    const m = JSON.parse(e.data);
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error')
      errs.push(m.params.args.map(a => a.value || a.description).join(' '));
    if (m.method === 'Runtime.exceptionThrown')
      errs.push('EXC ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
    if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  });
  const send = (method, params = {}) => new Promise(res => {
    const i = ++id; pending.set(i, res);
    ws.send(JSON.stringify({ id: i, method, params }));
  });
  const ev = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) return 'ERR ' + (r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
    return r.result?.result?.value;
  };

  await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1920, height: 1080, deviceScaleFactor: 1, mobile: false });
  await wait(1800);
  await ev('document.fonts.ready.then(()=>true)');

  for (const [file, setup] of SHOTS) {
    const info = await ev(`(async()=>{
      // 上一张可能开过弹层，先收干净，否则会污染这一张
      document.getElementById('exitDialog')?.classList.remove('is-open');
      ${setup};
      await new Promise(r=>setTimeout(r,620));
      // 弹层不在 .screen 里，所以先找弹层内的焦点
      const f = document.querySelector('#exitDialog.is-open .is-focused')
             || document.querySelector('.screen.is-active .is-focused');
      return f ? (f.className + ' | ' + (f.innerText||'').split('\\n')[0]) : '无焦点';
    })()`);
    await wait(380);
    const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    writeFileSync(join(OUT, file + '.png'), Buffer.from(shot.result.data, 'base64'));
    console.log(file.padEnd(18), info);
  }

  console.log('console errors:', errs.length ? JSON.stringify(errs.slice(0, 8)) : 'none');
  ws.close(); proc.kill(); process.exit(0);
}

main().catch(e => { console.error(e.message); proc.kill(); process.exit(1); });
