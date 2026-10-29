// 极简 CDP 客户端：起一个 headless Chrome、开一个本地文件页、按 clip 1:1 截图、求值。
// build / verify / shots 三个脚本共用它，避免三份几乎相同的启动样板。
import { spawn } from 'node:child_process';
import { decodePNG } from './.png.mjs';

export const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const wait = ms => new Promise(r => setTimeout(r, ms));

export async function openPage({ file, width = 1920, height = 1080, port = 9500, readySelector = null }) {
  const proc = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--force-color-profile=srgb', '--force-device-scale-factor=1',
    '--allow-file-access-from-files', '--disable-lcd-text',
    `--remote-debugging-port=${port}`, `--window-size=${width},${height}`, 'file://' + file,
  ], { stdio: 'ignore' });

  const endpoint = `http://127.0.0.1:${port}`;
  let target = null;
  for (let i = 0; i < 60; i++) {
    await wait(250);
    try {
      const list = await (await fetch(`${endpoint}/json`)).json();
      target = list.find(t => t.type === 'page' && decodeURIComponent(t.url).endsWith(file.split('/').pop()));
      if (target) break;
    } catch { }
  }
  if (!target) { proc.kill(); throw new Error('连不上 Chrome'); }

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise(r => ws.addEventListener('open', r, { once: true }));
  let id = 0; const pending = new Map();
  ws.addEventListener('message', e => {
    const m = JSON.parse(e.data);
    if (pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  });
  const send = (method, params = {}) => new Promise(res => {
    const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params }));
  });

  await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await wait(600);

  // 等字体真正就位：不许第一帧是回落字体，也不许量到回落字体的宽度
  for (let i = 0; i < 40; i++) {
    const r = await send('Runtime.evaluate', {
      expression: `document.fonts.ready.then(()=>document.readyState)` , awaitPromise: true, returnByValue: true,
    });
    const ok = r.result?.result?.value === 'complete'
      && (!readySelector || (await send('Runtime.evaluate', {
        expression: `!!document.querySelector(${JSON.stringify(readySelector)})`, returnByValue: true,
      })).result?.result?.value);
    if (ok) break;
    await wait(150);
  }

  const api = {
    send,
    /** 求值并取回 JS 值 */
    async eval(expression) {
      const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
      if (r.result?.exceptionDetails) throw new Error('eval 失败: ' + JSON.stringify(r.result.exceptionDetails).slice(0, 400));
      return r.result?.result?.value;
    },
    /** 按 clip 1:1 截图 → {w,h,ch,data} + 方便用的 get(x,y) */
    async shoot(clip) {
      const r = await send('Page.captureScreenshot', {
        format: 'png', captureBeyondViewport: false,
        clip: { x: clip.x, y: clip.y, width: clip.w, height: clip.h, scale: clip.scale ?? 1 },
      });
      const img = decodePNG(Buffer.from(r.result.data, 'base64'));
      if (Math.round(img.w) !== Math.round(clip.w * (clip.scale ?? 1))) {
        throw new Error(`截图宽 ${img.w} ≠ 请求 ${clip.w * (clip.scale ?? 1)}`);
      }
      img.at = (x, y) => {
        const i = (y * img.w + x) * img.ch;
        return [img.data[i], img.data[i + 1], img.data[i + 2]];
      };
      return img;
    },
    async close() { try { ws.close(); } catch { } proc.kill(); },
  };
  return api;
}

/* ---------- 位图度量工具（与 v1 同一套口径，便于跨版本对比） ---------- */
const near = (p, c, tol = 2) => Math.abs(p[0] - c[0]) <= tol && Math.abs(p[1] - c[1]) <= tol && Math.abs(p[2] - c[2]) <= tol;

/** 与某色「不同」的像素（容差 tol）→ {x0,y0,x1,y1,n}；img 为该区域裁片 */
export function bboxOfDiff(img, base, tol = 6) {
  let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1, n = 0;
  for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) {
    if (!near(img.at(x, y), base, tol)) {
      n++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
    }
  }
  return n ? { x0, y0, x1, y1, n } : null;
}

/** 某色像素（±tol）→ bbox */
export function bboxOfColor(img, c, tol = 6) {
  let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1, n = 0;
  for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) {
    if (near(img.at(x, y), c, tol)) {
      n++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
    }
  }
  return n ? { x0, y0, x1, y1, n } : null;
}

/**
 * 子像素竖边 / 横边：用整行（列）覆盖率积分，不是单列判色。
 * 边所在坐标 = 首个非零行列 + (1 − 该行列覆盖率 / 满覆盖)
 */
export function subpixelEdge(img, base, axis, from, to, scanFrom, scanTo, band) {
  const cov = [];
  for (let s = scanFrom; s <= scanTo; s++) {
    let sum = 0;
    for (let i = from; i <= to; i++) {
      const p = axis === 'x' ? img.at(s, i) : img.at(i, s);
      const d = Math.abs(p[0] - base[0]) + Math.abs(p[1] - base[1]) + Math.abs(p[2] - base[2]);
      sum += Math.min(1, d / 3 / band);
    }
    cov.push(sum / (to - from + 1));
  }
  // 找到覆盖率首次显著 > 0 的位置
  let k = 0; while (k < cov.length && cov[k] < 0.02) k++;
  if (k === cov.length) return null;
  const full = Math.max(...cov);
  return { at: scanFrom + k + (1 - cov[k] / full), cov: cov[k], full };
}

/** 圆角半径实测：从左上角沿对角线找第一个非底色像素，再用直边切点求 r */
export function cornerRadius(img, base, tol = 6) {
  // 底片左上角 (0,0) 之外是「外底」，卡片在内部。取卡片色
  const card = img.at(Math.round(img.w / 2), Math.round(img.h / 2));
  // 沿顶点行 y=0 找第一列是卡片的 x → 水平切点
  let xh = -1;
  for (let x = 0; x < img.w; x++) if (near(img.at(x, 0), card, tol)) { xh = x; break; }
  let yv = -1;
  for (let y = 0; y < img.h; y++) if (near(img.at(0, y), card, tol)) { yv = y; break; }
  return { card, xh, yv, r: (xh + yv) / 2 };
}
