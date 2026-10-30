#!/usr/bin/env node
/**
 * LiveHub 图标 · 交付位图 → Android res 同步器
 *
 * 设计侧交付位图在 design/brand/wordmark/export/（平铺，按档位命名）；
 * Android 侧取用在 android/app/src/main/res/。本脚本只做一件事：
 * 把前者拷到后者，并在**同一次运行内**逐档 md5 断言。
 *
 * 覆盖 5 档 mipmap 启动图标。banner 不走这里 —— export/banner-320.png 是
 * 带 alpha 的圆角交付档，res/tv_banner.png 是满版 bleed，圆角由 TV launcher
 * 裁，两者不是同一张图。
 *
 * md5 用 node:crypto 现算，不 shell out —— 判据不能依赖本机 md5 命令的方言。
 *
 * 用法
 *   node android/tools/sync-icons.mjs           只比对（默认），不写任何文件
 *   node android/tools/sync-icons.mjs --write   先拷贝再比对
 *
 * 任一档 md5 不一致 → 打印期望/实际并 exit 1。
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname, resolve } from 'node:path';

const ROOT = resolve(dirname(new URL(import.meta.url).pathname), '../..');
const EXPORT = join(ROOT, 'design/brand/wordmark/export');
const RES = join(ROOT, 'android/app/src/main/res');

const MAP = [
  ['square-48-B.png', 'mipmap-mdpi/ic_launcher.png'],
  ['square-72.png', 'mipmap-hdpi/ic_launcher.png'],
  ['square-96.png', 'mipmap-xhdpi/ic_launcher.png'],
  ['square-144.png', 'mipmap-xxhdpi/ic_launcher.png'],
  ['square-192.png', 'mipmap-xxxhdpi/ic_launcher.png'],
];

const WRITE = process.argv.slice(2).includes('--write');
const md5 = (f) => createHash('md5').update(readFileSync(f)).digest('hex');
const size = (f) => { const b = readFileSync(f); return `${b.readUInt32BE(16)}×${b.readUInt32BE(20)}`; };

let bad = 0;
console.log('\nres 目标                              源               尺寸      期望 md5       实际 md5       一致');
console.log('─'.repeat(97));
for (const [src, dst] of MAP) {
  const s = join(EXPORT, src);
  const d = join(RES, dst);
  if (!existsSync(s)) { console.log(`${dst.padEnd(37)}${src.padEnd(17)}（export 里没有这个文件）`); bad++; continue; }
  if (WRITE) { mkdirSync(dirname(d), { recursive: true }); writeFileSync(d, readFileSync(s)); }
  const want = md5(s);
  const got = existsSync(d) ? md5(d) : null;
  const same = got === want;
  if (!same) bad++;
  console.log(dst.padEnd(37) + src.padEnd(17) + size(s).padEnd(10) + want.slice(0, 8).padEnd(16) +
    (got ? got.slice(0, 8) : '（不存在）').padEnd(15) + (same ? '✓' : '✗'));
  if (!same) {
    console.log(`\n  ✗ ${src} → ${dst}`);
    console.log(`    期望 ${want}  ${size(s)}  （${s}）`);
    console.log(`    实际 ${got || '（不存在）'}  ${got ? size(d) : '—'}  （${d}）\n`);
  }
}
console.log(bad
  ? `\n${MAP.length - bad}/${MAP.length} 一致，${bad} 档不一致。`
  : `\n${MAP.length}/${MAP.length} 一致 ✓`);
process.exit(bad ? 1 : 0);
