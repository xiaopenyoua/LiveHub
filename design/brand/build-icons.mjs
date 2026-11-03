#!/usr/bin/env node
/**
 * LiveHub 应用图标 · 设计源 → 交付位图光栅化器
 *
 * 输入（唯一设计源，路径相对仓库根）
 *   design/brand/wordmark/src/square-48-B.svg       48 画布（LH 构成，竖干阈值以下的交付版）
 *   design/brand/wordmark/src/square-72.svg         72 画布（LH）
 *   design/brand/wordmark/src/square-96.svg         96 画布（完整 LiveHub）
 *   design/brand/wordmark/src/square-144.svg        144 画布（完整）
 *   design/brand/wordmark/src/square-192.svg        192 画布（完整）
 *   design/brand/wordmark/src/banner-16x9.svg       圆角 banner（展示档）
 *
 * 输出（交付位图，平铺在 design/brand/wordmark/export/）
 *   square-48-B.png    48       → app/livehub{,_tv} …/mipmap-mdpi/ic_launcher.png
 *   square-72.png      72       → app/livehub{,_tv} …/mipmap-hdpi/ic_launcher.png
 *   square-96.png      96       → app/livehub{,_tv} …/mipmap-xhdpi/ic_launcher.png
 *   square-144.png     144      → app/livehub{,_tv} …/mipmap-xxhdpi/ic_launcher.png
 *   square-192.png     192      → app/livehub{,_tv} …/mipmap-xxxhdpi/ic_launcher.png
 *   banner-320.png     320×180  圆角展示档，给 design/v1.0.0/icon.html 看；不同步进任何平台目录
 *
 * 本脚本只读 wordmark/src/*.svg、只写 wordmark/export/*.png，跑完不碰 app/。
 * 把位图送进 app/ 的唯一入口是 app/tools/sync-icons.mjs（字节拷贝 + 逐档 md5 断言）——
 * 设计侧不持有开发侧的写权限。
 *
 * banner 有两种图：export/banner-320.png 是带 alpha 的圆角展示档（本脚本产出），
 * TV launcher 用的满版 bleed banner 是**手工档**（真源 banner-16x9-bleed.svg，
 * 渲染一次、人工确认后放置），不在本脚本里，见 design/brand/README.md。
 *
 * 用法
 *   node design/brand/build-icons.mjs            光栅化并写进 wordmark/export/，同时与现有交付比对
 *   node design/brand/build-icons.mjs --check    只在内存里光栅化后比对，一个文件都不写
 *   node design/brand/build-icons.mjs --out DIR  写到别的目录（默认 wordmark/export/）
 *
 * 光栅化用本机 Chrome 的 DevTools 协议：viewport 锁死为目标像素、deviceScaleFactor=1、
 * 默认背景透明。所以产物是「在自己的坐标系里重新光栅化」，不是大图缩下来的。
 */
import { spawn } from 'node:child_process';
import { mkdirSync, existsSync, readFileSync, rmSync, mkdtempSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const ROOT = resolve(dirname(new URL(import.meta.url).pathname), '../..');
const SVG_DIR = join(ROOT, 'design/brand/wordmark/src');

const TIERS = [
  { svg: 'square-48-B.svg', px: 48, comp: 'LH' },
  { svg: 'square-72.svg', px: 72, comp: 'LH' },
  { svg: 'square-96.svg', px: 96, comp: 'LiveHub' },
  { svg: 'square-144.svg', px: 144, comp: 'LiveHub' },
  { svg: 'square-192.svg', px: 192, comp: 'LiveHub' },
];
const BANNER = { src: 'banner-16x9.svg', png: 'banner-320.png', w: 320, h: 180 };

const argv = process.argv.slice(2);
const has = (f) => argv.includes(f);
const opt = (f, d) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const CHECK = has('--check');
const OUT = resolve(ROOT, opt('--out', 'design/brand/wordmark/export'));

/** 输出恒为设计侧交付位图（平铺、按尺寸命名）；本脚本不写 app/ */
const outPath = (png) => join(OUT, png);

function findChrome() {
  const list = [
    process.env.CHROME_PATH,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    '/usr/bin/google-chrome', '/usr/bin/chromium',
  ].filter(Boolean);
  const hit = list.find(existsSync);
  if (!hit) throw new Error('没找到 Chrome/Chromium；用 CHROME_PATH 指一个');
  return hit;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 极简 CDP 客户端：只需要 navigate + 定尺寸 + 截图三件事。 */
async function launchChrome() {
  const profile = mkdtempSync(join(tmpdir(), 'lh-icons-'));
  const proc = spawn(findChrome(), [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--remote-debugging-port=0',
    `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
    '--hide-scrollbars', '--mute-audio', '--disable-background-timer-throttling',
    '--disable-renderer-backgrounding', '--disable-features=Translate,MediaRouter',
  ], { stdio: ['ignore', 'pipe', 'pipe'] });

  let buf = '';
  const wsUrl = await new Promise((res, rej) => {
    const on = (d) => {
      buf += d.toString();
      const m = buf.match(/ws:\/\/[^\s]+/);
      if (m) res(m[0]);
    };
    proc.stdout.on('data', on);
    proc.stderr.on('data', on);
    setTimeout(() => rej(new Error('Chrome 启动超时')), 20000);
  });

  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('CDP 连接失败')); });
  let id = 0;
  const pending = new Map();
  const listeners = new Map();
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { res, rej } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? rej(new Error(msg.error.message)) : res(msg.result);
    } else if (msg.method) {
      (listeners.get((msg.sessionId || '') + '|' + msg.method) || []).forEach((f) => f(msg.params));
    }
  };
  const send = (method, params = {}, sessionId) => new Promise((res, rej) => {
    const mid = ++id;
    pending.set(mid, { res, rej });
    ws.send(JSON.stringify(sessionId ? { id: mid, method, params, sessionId } : { id: mid, method, params }));
  });
  const once = (sessionId, method) => new Promise((res) => {
    const k = (sessionId || '') + '|' + method;
    const fn = (p) => { listeners.set(k, (listeners.get(k) || []).filter((f) => f !== fn)); res(p); };
    listeners.set(k, [...(listeners.get(k) || []), fn]);
  });

  return {
    async page(width, height) {
      const { targetId } = await send('Target.createTarget', { url: 'about:blank', newWindow: true });
      const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
      const S = (m, p) => send(m, p, sessionId);
      await S('Page.enable');
      await S('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
      await S('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
      return {
        async shot() {
          const { data } = await S('Page.captureScreenshot', {
            format: 'png', clip: { x: 0, y: 0, width, height, scale: 1 },
          });
          return Buffer.from(data, 'base64');
        },
        async open(url) {
          const loaded = once(sessionId, 'Page.loadEventFired');
          await S('Page.navigate', { url });
          await Promise.race([loaded, sleep(4000)]);
          await sleep(120);
        },
        close: () => send('Target.closeTarget', { targetId }).catch(() => {}),
      };
    },
    close() { try { ws.close(); } catch {} try { proc.kill('SIGKILL'); } catch {} rmSync(profile, { recursive: true, force: true, maxRetries: 3 }); },
  };
}

function pngSize(b) {
  if (b.readUInt32BE(0) !== 0x89504e47) throw new Error('不是 PNG');
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
}
const md5buf = (b) => createHash('md5').update(b).digest('hex');

const browser = await launchChrome();
const rows = [];
try {
  for (const t of TIERS) {
    const svg = join(SVG_DIR, t.svg);
    if (!existsSync(svg)) throw new Error('缺设计源：' + svg);
    const out = outPath(t.svg.replace(/\.svg$/, '.png'));
    const p = await browser.page(t.px, t.px);
    await p.open('file://' + svg);
    const buf = await p.shot();
    await p.close();
    if (!CHECK) { mkdirSync(dirname(out), { recursive: true }); writeFileSync(out, buf); }
    const size = pngSize(buf);
    const same = existsSync(out) && readFileSync(out).equals(buf);
    rows.push({
      tier: t.svg.replace(/\.svg$/, ''), px: String(t.px), comp: t.comp, src: t.svg,
      out: `${size.w}×${size.h}`,
      md5: md5buf(buf).slice(0, 8),
      same,
    });
  }

  // banner 展示档：320×180 圆角版（RGBA，角透明）。
  const bOut = outPath(BANNER.png);
  const bp = await browser.page(BANNER.w, BANNER.h);
  await bp.open('file://' + join(SVG_DIR, BANNER.src));
  const bBuf = await bp.shot();
  await bp.close();
  if (!CHECK) { mkdirSync(dirname(bOut), { recursive: true }); writeFileSync(bOut, bBuf); }
  const bSize = pngSize(bBuf);
  rows.push({
    tier: BANNER.png.replace(/\.png$/, ''), px: `${BANNER.w}×${BANNER.h}`, comp: 'banner', src: BANNER.src,
    out: `${bSize.w}×${bSize.h}`,
    md5: md5buf(bBuf).slice(0, 8),
    same: existsSync(bOut) && readFileSync(bOut).equals(bBuf),
  });
} finally {
  browser.close();
}

console.log('\n档位          像素        构成      设计源               产物      md5       现有交付');
console.log('─'.repeat(88));
for (const r of rows) {
  console.log(r.tier.padEnd(14) + r.px.padEnd(12) + r.comp.padEnd(10) + r.src.padEnd(21) +
    String(r.out).padEnd(9) + r.md5.padEnd(10) + (r.same ? '✓ 一致' : '△ 不同'));
}

const diff = rows.filter((r) => !r.same);
console.log(diff.length
  ? `\n与现有交付有 ${diff.length} 项不同（多为边缘抗锯齿差异）；确认后不带 --check 跑一次更新 export/，再由 app/tools/sync-icons.mjs --write 落盘到平台目录。`
  : '\n与现有交付逐字节一致 ✓');

if (!CHECK) console.log(`\n已导出 ${OUT}，app/ 未改动。`);
