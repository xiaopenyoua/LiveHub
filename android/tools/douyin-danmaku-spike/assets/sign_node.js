// 只给桌面 spike 用：把 sign.js 跑在**真 V8**（node）里，输出 signature。
//
// 它存在的唯一原因：生产端跑这段 JS 的是 Android WebView（V8），不是 Rhino。
// 桌面 JVM 上没有 WebView，node 是「同一类引擎」的最接近替身——
// 所以这个脚本是**生产环境的模拟器**，不是另一种实现，不许拿它当产品方案。
//
// 用法：node sign_node.js <md5> <sign.js 路径>
const fs = require('fs');
const vm = require('vm');

const [md5, jsPath] = process.argv.slice(2);
if (!md5 || !jsPath) {
  console.error('usage: node sign_node.js <md5> <sign.js>');
  process.exit(2);
}

// V8 在 sloppy mode 下本来就按 Annex B 提升块内函数声明，所以这里不需要
// Rhino 那条「把 w_0x42f5 挪到块首」的补丁；let→var 也不必要（V8 支持 let）。
// 为了和 Rhino 那边**输入完全一致**，还是做一次 let→var——差异越小，结论越干净。
const src = fs.readFileSync(jsPath, 'utf8').replace(/\blet\b/g, 'var');

const sandbox = {};
sandbox.window = sandbox;
sandbox.self = sandbox;
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(src, sandbox, { filename: 'sign.js' });

const sig = sandbox.get_sign(md5);
process.stdout.write(String(sig).trim());
