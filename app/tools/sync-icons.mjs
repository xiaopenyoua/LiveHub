#!/usr/bin/env node
/**
 * LiveHub 图标 · 设计交付位图 → Flutter 各包的平台图标目录同步器
 *
 * 设计侧交付位图在 design/brand/wordmark/export/（平铺，按尺寸命名）；
 * 开发侧取用在 app/livehub 与 app/livehub_tv 的 Android res 目录。本脚本只做一件事：
 * 把前者拷到后者，并在**同一次运行内**逐档 md5 断言。
 *
 * 覆盖范围
 *   - app/livehub_tv/android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
 *   - app/livehub/android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
 *
 * 不覆盖（各有自己的尺寸集或分层，必须用平台原生工具另行产出）
 *   - 手机端自适应图标的前景层 ic_launcher_foreground、圆形层 ic_launcher_round
 *     （设计交付的是整枚方形图标，不含 Android 自适应分层）
 *   - iOS AppIcon.appiconset（20–1024 共 18 档）
 *   - macOS AppIcon.appiconset（16–1024 共 10 档）
 *   - Windows app_icon.ico（需要 ico 封装）
 *   - TV banner：app/livehub_tv/android/app/src/main/res/mipmap-xhdpi/ic_banner.png
 *     是**手工档**（满版 RGB，圆角交给 launcher 裁），不在自动管线内。
 *
 * md5 用 node:crypto 现算，不 shell out —— 判据不能依赖本机 md5 命令的方言。
 *
 * 用法
 *   node app/tools/sync-icons.mjs           只比对（默认），不写任何文件
 *   node app/tools/sync-icons.mjs --write   先拷贝再比对
 *
 * 任一档 md5 不一致 → 打印期望/实际并 exit 1。
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, dirname, resolve } from 'node:path';

const ROOT = resolve(dirname(new URL(import.meta.url).pathname), '../..');
const EXPORT = join(ROOT, 'design/brand/wordmark/export');

const TIERS = [
  { density: 'mipmap-mdpi', src: 'square-48-B.png', px: 48 },
  { density: 'mipmap-hdpi', src: 'square-72.png', px: 72 },
  { density: 'mipmap-xhdpi', src: 'square-96.png', px: 96 },
  { density: 'mipmap-xxhdpi', src: 'square-144.png', px: 144 },
  { density: 'mipmap-xxxhdpi', src: 'square-192.png', px: 192 },
];

const TARGETS = [
  { name: 'livehub_tv', res: join(ROOT, 'app/livehub_tv/android/app/src/main/res') },
  { name: 'livehub', res: join(ROOT, 'app/livehub/android/app/src/main/res') },
];

const WRITE = process.argv.slice(2).includes('--write');
const md5 = (f) => createHash('md5').update(readFileSync(f)).digest('hex');
const size = (f) => {
  const b = readFileSync(f);
  return `${b.readUInt32BE(16)}×${b.readUInt32BE(20)}`;
};

let bad = 0;
console.log('\n包 / 目标                                      源               尺寸      期望 md5       实际 md5       一致');
console.log('─'.repeat(108));

for (const target of TARGETS) {
  for (const tier of TIERS) {
    const s = join(EXPORT, tier.src);
    const rel = `${tier.density}/ic_launcher.png`;
    const d = join(target.res, rel);

    if (!existsSync(s)) {
      console.log(`${(target.name + ' ' + rel).padEnd(47)}${tier.src.padEnd(17)}（export 里没有这个文件）`);
      bad++;
      continue;
    }

    const srcSize = size(s);
    if (srcSize !== `${tier.px}×${tier.px}`) {
      console.log(`${(target.name + ' ' + rel).padEnd(47)}${tier.src.padEnd(17)}尺寸 ${srcSize}，期望 ${tier.px}×${tier.px}`);
      bad++;
      continue;
    }

    if (WRITE) {
      mkdirSync(dirname(d), { recursive: true });
      writeFileSync(d, readFileSync(s));
    }

    const want = md5(s);
    const got = existsSync(d) ? md5(d) : null;
    const same = got === want;
    if (!same) bad++;
    console.log(
      (target.name + ' ' + rel).padEnd(47) +
      tier.src.padEnd(17) +
      srcSize.padEnd(10) +
      want.slice(0, 8).padEnd(16) +
      (got ? got.slice(0, 8) : '（不存在）').padEnd(15) +
      (same ? '✓' : '✗')
    );
  }
}

console.log('\n不自动同步（手工档 / 平台原生工具）：');
console.log('  TV banner   app/livehub_tv/android/app/src/main/res/mipmap-xhdpi/ic_banner.png');
console.log('  手机自适应  ic_launcher_foreground / ic_launcher_round');
console.log('  iOS         app/livehub/ios/Runner/Assets.xcassets/AppIcon.appiconset/');
console.log('  macOS       app/livehub/macos/Runner/Assets.xcassets/AppIcon.appiconset/');
console.log('  Windows     app/livehub/windows/runner/resources/app_icon.ico');
console.log('              app/livehub_tv/windows/runner/resources/app_icon.ico');

const total = TARGETS.length * TIERS.length;
console.log(bad
  ? `\n${total - bad}/${total} 一致，${bad} 档不一致。${WRITE ? '' : ' 先确认 export/ 已由 build-icons 更新，再跑 --write。'}`
  : `\n${total}/${total} 一致 ✓`);
process.exit(bad ? 1 : 0);
