#!/usr/bin/env node
/**
 * LiveHub 应用图标 · 产物生成器
 *
 * 输入（唯一设计源，路径相对仓库根）
 *   design/brand/wordmark/src/square-48-B.svg       48 画布（LH 构成，竖干阈值以下的交付版）
 *   design/brand/wordmark/src/square-72.svg         72 画布（LH）
 *   design/brand/wordmark/src/square-96.svg         96 画布（完整 LiveHub）
 *   design/brand/wordmark/src/square-144.svg        144 画布（完整）
 *   design/brand/wordmark/src/square-192.svg        192 画布（完整）
 *   design/brand/wordmark/src/banner-16x9-bleed.svg TV banner 满版矢量（圆角交给 launcher 裁）
 *
 * 输出（交付位图，平铺在 design/brand/wordmark/export/）
 *   square-48-B.png    48       → mipmap-mdpi/ic_launcher.png      （由 sync-icons.mjs 同步）
 *   square-72.png      72       → mipmap-hdpi/ic_launcher.png      （同上）
 *   square-96.png      96       → mipmap-xhdpi/ic_launcher.png     （同上）
 *   square-144.png     144      → mipmap-xxhdpi/ic_launcher.png    （同上）
 *   square-192.png     192      → mipmap-xxxhdpi/ic_launcher.png   （同上）
 *   banner-320.png     320×180  手工档：带 alpha 的圆角预览版，**不**同步进 res/
 *                               （res 里那张 tv_banner.png 是满版 bleed，圆角交 launcher 裁）
 *
 * 本脚本只读 wordmark/src/*.svg、只写 wordmark/export/*.png，跑完不碰 android/。
 * 把位图送进 android/ 的唯一入口是 android/tools/sync-icons.mjs（字节拷贝 + 逐档 md5 断言）——
 * 设计侧不持有开发侧的写权限。
 *
 * 用法
 *   node design/brand/build-icons.mjs            光栅化并写进 wordmark/export/，同时与现有交付逐档比对
 *   node design/brand/build-icons.mjs --check    只在内存里光栅化后比对，一个文件都不写
 *   node design/brand/build-icons.mjs --out DIR  写到别的目录（默认 wordmark/export/）
 *
 * 光栅化用本机 Chrome 的 DevTools 协议：viewport 锁死为目标像素、deviceScaleFactor=1、
 * 默认背景透明。所以产物是「在自己的坐标系里重新光栅化」，不是大图缩下来的。
 *
 * 自适应图标的三层（foreground / monochrome / colors / anydpi-v26 / anydpi-v33）是手写矢量，
 * 7 条 path 与 square.svg 逐字节同源，不需要再过一道生成器；本脚本只核对它们在不在。
 */
import { spawn } from 'node:child_process';
import { mkdirSync, existsSync, readFileSync, rmSync, mkdtempSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const ROOT = resolve(dirname(new URL(import.meta.url).pathname), '../..');
const SVG_DIR = join(ROOT, 'design/brand/wordmark/src');
const RES = join(ROOT, 'android/app/src/main/res');

const TIERS = [
  { dir: 'mipmap-mdpi', svg: 'square-48-B.svg', px: 48, comp: 'LH' },
  { dir: 'mipmap-hdpi', svg: 'square-72.svg', px: 72, comp: 'LH' },
  { dir: 'mipmap-xhdpi', svg: 'square-96.svg', px: 96, comp: 'LiveHub' },
  { dir: 'mipmap-xxhdpi', svg: 'square-144.svg', px: 144, comp: 'LiveHub' },
  { dir: 'mipmap-xxxhdpi', svg: 'square-192.svg', px: 192, comp: 'LiveHub' },
];
const BANNER = { dir: 'drawable-xhdpi', file: 'tv_banner.png', src: 'banner-16x9-bleed.svg', png: 'banner-320.png' };
const HANDWRITTEN = [
  'mipmap-anydpi-v26/ic_launcher.xml',
  'mipmap-anydpi-v33/ic_launcher.xml',
  'drawable/ic_launcher_foreground.xml',
  'drawable/ic_launcher_monochrome.xml',
  'values/ic_launcher_colors.xml',
];

const argv = process.argv.slice(2);
const has = (f) => argv.includes(f);
const opt = (f, d) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const CHECK = has('--check');
const OUT = resolve(ROOT, opt('--out', 'design/brand/wordmark/export'));

/** 输出恒为设计侧交付位图（平铺、按档位命名）；本脚本不写 android/ */
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
const md5 = (f) => createHash('md5').update(readFileSync(f)).digest('hex');
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
    const del = join(RES, t.dir, 'ic_launcher.png');
    rows.push({
      tier: t.dir, px: String(t.px), comp: t.comp, src: t.svg,
      out: `${size.w}×${size.h}`,
      md5: md5buf(buf).slice(0, 8),
      del: existsSync(del) ? md5(del).slice(0, 8) : '缺',
      same: existsSync(del) && md5buf(buf) === md5(del),
    });
  }

  // banner：交付档 320×180，用 bleed 版矢量（满版无圆角）重新光栅化 ——
  // 圆角由 TV launcher 自己裁，画进图里就是双重圆角。
  const bOut = outPath(BANNER.png);
  const bp = await browser.page(320, 180);
  await bp.open('file://' + join(SVG_DIR, BANNER.src));
  const bBuf = await bp.shot();
  await bp.close();
  if (!CHECK) { mkdirSync(dirname(bOut), { recursive: true }); writeFileSync(bOut, bBuf); }
  const bDel = join(RES, BANNER.dir, BANNER.file);
  rows.push({
    tier: BANNER.dir, px: '320×180', comp: 'banner', src: BANNER.src,
    out: (s => `${s.w}×${s.h}`)(pngSize(bBuf)),
    md5: md5buf(bBuf).slice(0, 8),
    del: existsSync(bDel) ? md5(bDel).slice(0, 8) : '缺',
    same: existsSync(bDel) && md5buf(bBuf) === md5(bDel),
  });
} finally {
  browser.close();
}

console.log('\n档位              像素      构成      设计源               产物      md5       现有交付   一致');
console.log('─'.repeat(106));
for (const r of rows) {
  console.log(r.tier.padEnd(17) + r.px.padEnd(10) + r.comp.padEnd(10) + r.src.padEnd(21) +
    String(r.out).padEnd(9) + r.md5.padEnd(10) + r.del.padEnd(11) + (r.same ? '✓' : '△'));
}

let missing = HANDWRITTEN.filter((f) => !existsSync(join(RES, f)));
console.log(`\n自适应三层 / 颜色：${HANDWRITTEN.length - missing.length}/${HANDWRITTEN.length} 在 res 里` +
  (missing.length ? '，缺：' + missing.join('、') : ' ✓'));

const diff = rows.filter((r) => !r.same);
console.log(diff.length
  ? `\n与现有交付有 ${diff.length} 项 md5 不同（多为边缘抗锯齿差异）；核对后由 android/tools/sync-icons.mjs --write 落盘。`
  : '\n与现有交付逐字节一致 ✓');

if (!CHECK) console.log(`\n已导出 ${OUT}，android/ 未改动。`);
