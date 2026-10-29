// 开屏取证：截图 + 版面/颜色/时序实测。
// 用法：node design/.splash-shots.mjs
// 输出：design/brand/shots/splash-*.png 与 measure.json 的原始读数（stdout）
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG } from './.png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, 'brand', 'shots');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FILE = 'file://' + join(HERE, 'splash.html');
const PORT = 9489;
mkdirSync(OUT, { recursive: true });

const proc = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
  '--disable-lcd-text', '--force-color-profile=srgb', '--force-device-scale-factor=1',
  `--remote-debugging-port=${PORT}`, '--window-size=1920,1080', FILE + '?canvas=1&still=1'], { stdio: 'ignore' });
const wait = ms => new Promise(r => setTimeout(r, ms));

// 五个颜色值（03 §1）
const PAL = [
  ['#FFFFFF', [255, 255, 255]],
  ['#FF453A', [255, 69, 58]],
  ['#B4B4B4', [180, 180, 180]],
  ['#8E8E93', [142, 142, 147]],
  ['#000000', [0, 0, 0]],
];
function classify(p) {
  let best = { err: 1e9, name: '#000000', a: 0 };
  for (const [name, c] of PAL) {
    const den = c[0] * c[0] + c[1] * c[1] + c[2] * c[2];
    if (!den) continue;
    const a = Math.max(0, Math.min(1, (p[0] * c[0] + p[1] * c[1] + p[2] * c[2]) / den));
    const err = Math.hypot(p[0] - a * c[0], p[1] - a * c[1], p[2] - a * c[2]);
    if (err < best.err) best = { err, name, a };
  }
  return best;
}

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
  await send('Page.enable');

  const size = async (w, h) => send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false });
  await size(1920, 1080);

  const go = async (qs) => {
    await send('Page.navigate', { url: FILE + qs });
    for (let i = 0; i < 120; i++) {
      await wait(80);
      const ok = await ev('!!(window.__splash && window.__splash.ready)');
      if (ok === true) return;
    }
    throw new Error('页面没就绪：' + qs);
  };

  const shot = async (file, w, h) => {
    const s = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false, clip: { x: 0, y: 0, width: w, height: h, scale: 1 } });
    const buf = Buffer.from(s.result.data, 'base64');
    writeFileSync(join(OUT, file), buf);
    return buf;
  };

  const shots = [];
  const add = (f, n, l) => shots.push([f, n, l]);

  // ---------- 1. 就绪静止终态（主图） ----------
  await go('?canvas=1&still=1');
  const bufReady = await shot('splash-ready-1920.png', 1920, 1080);
  add('splash-ready-1920.png', '1920×1080', '就绪终态（四行有结论）');

  // ---------- 2. 连接中（seek t=900，慢就绪路径） ----------
  await go('?canvas=1&state=slow&t=900');
  await shot('splash-connecting-1920.png', 1920, 1080);
  add('splash-connecting-1920.png', '1920×1080', 't=900 四行连接中');

  // ---------- 3/4. 整体降级态（有网络 / 无网络） ----------
  await go('?canvas=1&state=allfail&net=on&still=1');
  const bufFail = await shot('splash-allfail-1920.png', 1920, 1080);
  add('splash-allfail-1920.png', '1920×1080', '四家全失败（有网络）');

  await go('?canvas=1&state=allfail&net=off&still=1');
  await shot('splash-offline-1920.png', 1920, 1080);
  add('splash-offline-1920.png', '1920×1080', '四家全失败（无网络）');

  // ---------- 5. 系统帧（段 1） ----------
  await go('?canvas=1&sys=1');
  const bufSys = await shot('splash-sys.png', 1920, 1080);
  add('splash-sys.png', '1920×1080', '段 1 系统开屏（居中 192px）');

  // ---------- 6. 720p ----------
  await size(1280, 720);
  await go('?canvas=1&still=1');
  await shot('splash-720.png', 1280, 720);
  add('splash-720.png', '1280×720', '720p 等比 ×0.667');
  await size(1920, 1080);

  // ---------- 7. 入场四帧 ----------
  for (const t of [0, 340, 600, 960]) {
    await go('?canvas=1&t=' + t);
    await shot(`splash-seq-t${t}.png`, 1920, 1080);
    add(`splash-seq-t${t}.png`, '1920×1080', `P0+${t}ms`);
  }

  // ---------- 8. 退场交叉的那一帧（T=960 → t=1060，E1 走一半、E2 走 42%）----------
  await go('?canvas=1&t=1060');
  await shot('splash-exit-1920.png', 1920, 1080);
  add('splash-exit-1920.png', '1920×1080', 'T+100ms：开屏在退、首页在涨');

  // ---------- 9. 退场落定后的首页 ----------
  await go('?canvas=1&t=1300');
  await shot('splash-home-1920.png', 1920, 1080);
  add('splash-home-1920.png', '1920×1080', 'B 之后：首页已在位');

  // ============ 实测 ============
  const geom = await ev(`(()=>{
    const R=s=>{const e=document.querySelector(s);if(!e)return null;const r=e.getBoundingClientRect();return {x:+r.x.toFixed(2),y:+r.y.toFixed(2),w:+r.width.toFixed(2),h:+r.height.toFixed(2),right:+r.right.toFixed(2),bottom:+r.bottom.toFixed(2)}};
    const stg=document.getElementById('stage').getBoundingClientRect();
    const off=(r)=>({x:+(r.x-stg.x).toFixed(2),y:+(r.y-stg.y).toFixed(2),w:r.w,h:r.h,right:+(r.right-stg.x).toFixed(2),cx:+((r.x-stg.x)+r.w/2).toFixed(2)});
    const wm=off(R('.wm'));
    const mark=off(R('.mark'));
    const rows=[...document.querySelectorAll('.st')].map(e=>{const r=e.getBoundingClientRect();return {bl:e.style.getPropertyValue('--bl'),left:+(r.x-stg.x).toFixed(2)}});
    const faces=[...document.querySelectorAll('.st .face')].map((e,i)=>({i,op:+getComputedStyle(e).opacity}));
    const fail={t:off(R('.fail .t')),d:off(R('.fail .d')),b:off(R('.fail .b'))};
    const bar=[...document.querySelectorAll('.st .face.late .bar')].map(e=>+getComputedStyle(e).width.replace('px',''));
    const font={inter:document.fonts.check('600 120px Inter'),live:document.fonts.check('400 22px Inter'),
      cjk:document.fonts.check('400 22px "PingFang SC"')};
    const cs=e=>{const s=getComputedStyle(e);return [s.color,s.backgroundColor,s.borderTopColor,s.outlineColor]};
    // 开屏层里出现的所有颜色值（计算样式，不含抗锯齿）
    const seen=new Set();
    document.querySelectorAll('.layer-splash, .layer-splash *').forEach(e=>{
      const s=getComputedStyle(e);
      ['color','backgroundColor','fill','stroke'].forEach(k=>{const v=s[k];if(v)seen.add(v)});
    });
    return {wm,mark,rows,faces,fail,bar,font,colors:[...seen],stage:{w:+stg.width.toFixed(2),h:+stg.height.toFixed(2)},
      brand:(()=>{const r=R('.home-brand');return r?{y:+(r.y-stg.y).toFixed(2),x:+(r.x-stg.x).toFixed(2),bl:+((r.y-stg.y)+.8633*30).toFixed(2)}:null})(),
      title:(()=>{const e=document.querySelector('.home-brand');return e?e.textContent:null})()};
  })()`);

  // 位图实测：图标墨迹 bbox / 字标墨迹 / 四行基线 / 颜色集合
  function ink(buf, x0, y0, x1, y1, thr = 0) {
    const img = decodePNG(buf), ch = img.ch;
    let minX = 1e9, minY = 1e9, maxX = -1, maxY = -1, n = 0;
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
      const i = (y * img.w + x) * ch;
      const v = Math.max(img.data[i], img.data[i + 1], img.data[i + 2]);
      if (v > thr) { n++; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; }
    }
    return n ? { x0: minX, y0: minY, x1: maxX, y1: maxY, w: maxX - minX + 1, h: maxY - minY + 1, n } : null;
  }
  // 子像素竖向边缘：用一条完全落在笔画内的竖带，按行覆盖率插值
  function vEdge(buf, x0, x1, y0, y1) {
    const img = decodePNG(buf), ch = img.ch;
    const sum = y => { let t = 0; for (let x = x0; x < x1; x++) { const i = (y * img.w + x) * ch; t += img.data[i]; } return t; };
    let full = 0, first = null, last = null;
    for (let y = y0; y < y1; y++) { const s = sum(y); if (s > full) full = s; if (s > 0) { if (first === null) first = y; last = y; } }
    if (first === null) return null;
    return { top: +(first + (1 - sum(first) / full)).toFixed(2), bottom: +(last + sum(last) / full).toFixed(2), full: full / 255 };
  }
  const g = decodePNG(bufReady);
  const ch = g.ch;
  const rowsInk = [695, 743, 791, 839].map(bl => ink(bufReady, 890, bl - 26, 946, bl + 8, 8));
  const wmInk = ink(bufReady, 600, 470, 1320, 600, 8);
  // cap 顶只认「L」的竖干：b 的 ascender 与 i 的点都比 cap 高 1.5px，
  // 整词墨顶会读成 494，用它判 496 会得到假失败。
  const wmEdge = vEdge(bufReady, 750, 762, 480, 600);
  const markInk = ink(bufReady, 800, 220, 1120, 450, 8);
  const sysInk = ink(bufSys, 700, 300, 1220, 780, 8);
  // 指示构件：四行各一条 16×4 横条 —— 横条顶边 = 基线−10，于是基线可被反推
  const barInk = [695, 743, 791, 839].map(bl => ink(bufReady, 855, bl - 16, 890, bl - 2, 8));
  // 徽章右缘 = 三角顶点 x：判据是「像素覆盖的最右列相同」，不要求覆盖率 100%
  const redRight = (() => {
    const im = decodePNG(bufReady);
    let m = -1;
    for (let y = 250; y < 292; y++) for (let x = 950; x < 1030; x++) {
      const i = (y * im.w + x) * im.ch, r = im.data[i], g2 = im.data[i + 1];
      if (r > 40 && g2 < r * 0.6) { if (x > m) m = x; }
    }
    return m;
  })();
  const apexRight = (() => {
    const im = decodePNG(bufReady);
    let m = -1;
    for (let y = 300; y < 366; y++) for (let x = 950; x < 1030; x++) {
      const i = (y * im.w + x) * im.ch, r = im.data[i], g2 = im.data[i + 1], b2 = im.data[i + 2];
      if (r > 40 && Math.abs(r - g2) < 6 && Math.abs(r - b2) < 6) { if (x > m) m = x; }
    }
    return m;
  })();
  // 降级态：标题 / 说明的墨迹 + 实心白胶囊的精确矩形
  const failTitle = ink(bufFail, 600, 665, 1320, 722, 8);
  const failDesc = ink(bufFail, 600, 722, 1320, 768, 8);
  const failBtn = ink(bufFail, 820, 768, 1100, 860, 8);

  // 颜色集合（整帧，按「黑×某本色」的线性混合分类）
  const hist = new Map(); let alien = 0, alienSample = [];
  for (let y = 0; y < g.h; y++) for (let x = 0; x < g.w; x++) {
    const i = (y * g.w + x) * ch;
    const p = [g.data[i], g.data[i + 1], g.data[i + 2]];
    const k = p[0] + ',' + p[1] + ',' + p[2];
    hist.set(k, (hist.get(k) || 0) + 1);
    if (p[0] || p[1] || p[2]) {
      const c = classify(p);
      if (c.err > 1.6) { alien++; if (alienSample.length < 6) alienSample.push(k + '→' + c.name + ' err' + c.err.toFixed(1)); }
    }
  }
  const pure = [...hist.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8);

  // ---------- 时序结构：从浏览器自己的动画对象上读 duration / delay ----------
  const structOf = async (cls) => {
    await go('?canvas=1&still=1');
    return ev(`(()=>{
      const st=document.getElementById('stage');
      ${cls ? `st.classList.add('${cls}');` : ''}
      st.classList.add('is-exiting');
      const rows=[...st.getAnimations({subtree:true})].map(a=>{
        const t=a.effect.getTiming();
        return {name:a.animationName||'(transition)',dur:t.duration,delay:t.delay,end:+a.effect.getComputedTiming().endTime.toFixed(0)};
      });
      st.classList.remove('is-exiting');
      ${cls ? `st.classList.remove('${cls}');` : ''}
      const cs=getComputedStyle(st);
      return {rows,vars:{dE1:cs.getPropertyValue('--dE1').trim(),dE2:cs.getPropertyValue('--dE2').trim(),
        dE3b:cs.getPropertyValue('--dE3b').trim(),delE3b:cs.getPropertyValue('--delE3b').trim(),dP4:cs.getPropertyValue('--dP4').trim()}};
    })()`);
  };
  const struct = await structOf(null);
  const structRM = await structOf('reduced');

  // ---------- 时序实测（跑满四条路径） ----------
  const runs = {};
  for (const [name, qs] of [
    ['ready', '?canvas=1&state=ready'],
    ['slow', '?canvas=1&state=slow'],
    ['timeout', '?canvas=1&state=timeout'],
    ['reduced', '?canvas=1&reduced=1&state=ready'],
    ['allfail', '?canvas=1&state=allfail'],
  ]) {
    await go(qs);
    for (let i = 0; i < 160; i++) {
      await wait(60);
      const s = await ev('JSON.parse(JSON.stringify(window.__splash))');
      if (s && (s.tHomeSettledB !== null || s.T === null)) { runs[name] = s; break; }
    }
    if (!runs[name]) runs[name] = await ev('JSON.parse(JSON.stringify(window.__splash))');
  }

  console.log('字体：' + JSON.stringify(geom.font));
  console.log('画布：' + JSON.stringify(geom.stage));
  console.log('\n[DOM] 图标视口   ' + JSON.stringify(geom.mark));
  console.log('[DOM] 字标盒     ' + JSON.stringify(geom.wm));
  console.log('[整帧] 图标墨迹  ' + JSON.stringify(markInk));
  console.log('[整帧] 徽章最右列 ' + redRight + '  三角顶点最右列 ' + apexRight + '  → ' + (redRight === apexRight ? '同一列 ✓' : '不同列 ✗'));
  console.log('[整帧] 字标墨迹  ' + JSON.stringify(wmInk));
  console.log('[子像素] L 竖干    cap 顶 ' + wmEdge.top + '  baseline ' + wmEdge.bottom + '  cap 高 ' + (wmEdge.bottom - wmEdge.top).toFixed(2));
  console.log('[整帧] 四行墨迹  ' + JSON.stringify(rowsInk.map(r => r && { top: r.y0, bottom: r.y1 })) );
  console.log('[整帧] 指示横条  ' + JSON.stringify(barInk.map(b => b && { x: b.x0 + '-' + b.x1, y: b.y0 + '-' + b.y1, w: b.w, h: b.h })) + '  → 反推基线 ' + JSON.stringify(barInk.map(b => b ? b.y0 + 10 : null)));
  console.log('[整帧] 降级态    标题 ' + JSON.stringify(failTitle && { x: failTitle.x0 + '-' + failTitle.x1, y: failTitle.y0 + '-' + failTitle.y1 }) +
    '  说明 ' + JSON.stringify(failDesc && { x: failDesc.x0 + '-' + failDesc.x1, y: failDesc.y0 + '-' + failDesc.y1 }) +
    '  按钮 ' + JSON.stringify(failBtn && { x: failBtn.x0 + '-' + failBtn.x1, y: failBtn.y0 + '-' + failBtn.y1, w: failBtn.w, h: failBtn.h }));
  console.log('[整帧] 系统帧墨迹 ' + JSON.stringify(sysInk));
  console.log('\n退出时序结构（浏览器动画对象上的真值）：');
  console.log('  普通    ' + JSON.stringify(struct.rows) + '\n          vars ' + JSON.stringify(struct.vars));
  console.log('  reduced ' + JSON.stringify(structRM.rows) + '\n          vars ' + JSON.stringify(structRM.vars));
  console.log('\n颜色集合（前 8 个出现最多的精确值）：');
  pure.forEach(([k, n]) => console.log('  ' + k.padEnd(14) + String(n).padStart(8) + (PAL.find(p => p[1].join(',') === k) ? '  ← 规范值 ' + PAL.find(p => p[1].join(',') === k)[0] : '  （抗锯齿混合）')));
  console.log('第四色（无法表示为 黑×某本色 的像素）：' + alien + (alienSample.length ? ' 例：' + alienSample.join(' | ') : ''));
  console.log('计算样式里的颜色值：' + JSON.stringify(geom.colors));
  console.log('\n时序：');
  for (const k of Object.keys(runs)) {
    const r = runs[k];
    console.log('  ' + k.padEnd(8) + ' tC=' + String(r.tC).padStart(4) + ' T=' + String(r.T).padStart(5) +
      ' 实测 A=' + String(r.tClearedA).padStart(4) + '/' + r.expectA +
      '  B=' + String(r.tHomeSettledB).padStart(4) + '/' + r.expectB +
      '  退场起=' + r.tExitStart + (r.reduced ? '  reduced' : ''));
  }
  console.log('\n文件'.padEnd(28) + '像素'.padEnd(12) + '内容');
  console.log('─'.repeat(76));
  for (const [f, s, l] of shots) console.log(f.padEnd(28) + s.padEnd(12) + l);
  console.log('─'.repeat(76));
  console.log('console errors: ' + (errs.length ? JSON.stringify(errs.slice(0, 6)) : 'none'));

  writeFileSync(join(OUT, '.splash-raw.json'), JSON.stringify({ redRight, apexRight, geom, markInk, wmInk, wmEdge, rowsInk, barInk, failTitle, failDesc, failBtn, sysInk, alien, pure, struct, structRM, runs }, null, 1));
  ws.close(); proc.kill(); process.exit(0);
}
main().catch(e => { console.error(e.message); proc.kill(); process.exit(1); });
