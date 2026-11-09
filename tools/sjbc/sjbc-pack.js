// sjbc-pack.js —— 手机内打包：源码 → 字节码 → v5 加密容器（.sjbc）
// 用法（AI.js Pro 的 run_script / 终端 node 都可以）：
//   node sjbc-pack.js <源码> <输出> [--cert <证书SHA256|APK|self>] [--check]
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { compile } = require('./ajs2sjbc.js');
const { buildV5, openV5 } = require('./pack_v5.js');
const { masterFromCert } = require('./keyderiv.js');

const HOST = ['print', 'len', 'push', 'slice0'];   // 必须与宿主实现一致

function certShaFromApk(p) {
  const tryCmd = (c, a) => { try { return execFileSync(c, a, { encoding: 'utf8' }); } catch (e) { return null; } };
  let o = tryCmd('apksigner', ['verify', '--print-certs', p]);
  if (o) { const m = o.match(/SHA-256 digest:\s*([0-9a-fA-F:]{64,})/); if (m) return m[1].replace(/:/g, '').toLowerCase(); }
  o = tryCmd('keytool', ['-printcert', '-jarfile', p]);
  if (o) { const m = o.match(/SHA256:\s*([0-9A-F:]{64,})/); if (m) return m[1].replace(/:/g, '').toLowerCase(); }
  throw new Error('读不出证书指纹：请用 apksigner verify --print-certs 查出后用 --cert <64位hex> 传入');
}

const argv = process.argv.slice(2);
if (argv.length < 2) { console.log('用法: node sjbc-pack.js <源码> <输出.sjbc> [--cert <sha256|apk|self>] [--check]'); process.exit(2); }
const srcPath = argv[0], outPath = argv[1];
const ci = argv.indexOf('--cert');
const certArg = ci >= 0 ? argv[ci + 1] : 'self';
const doCheck = argv.indexOf('--check') >= 0;
let certSha = '00'.repeat(32);
if (certArg !== 'self' && /^[0-9a-fA-F]{64}$/.test(certArg)) certSha = certArg.toLowerCase();
else if (certArg !== 'self') certSha = certShaFromApk(certArg);

const src = fs.readFileSync(srcPath, 'utf8');
const t0 = Date.now();
const c = compile(src, HOST);
const master = masterFromCert(certSha);
const blob = buildV5({ bytes: c.bytes, pool: c.pool, entries: c.entries }, master);
fs.mkdirSync(path.dirname(path.resolve(outPath)), { recursive: true });
fs.writeFileSync(outPath, blob);
console.log('源码: ' + srcPath);
console.log('字节码: ' + c.bytes.length + ' B / 函数 ' + c.chunks + ' / 常量 ' + c.pool.length);
console.log('密文: ' + outPath + '  ' + blob.length + ' B');
console.log('指纹: ' + (certArg === 'self' ? '(占位 self)' : certSha));
console.log('耗时: ' + (Date.now() - t0) + ' ms');
if (doCheck) {
  const back = openV5(blob, master);
  const n = back.poolText.split(String.fromCharCode(10)).filter(l => l.charAt(0) === 'N' || l.charAt(0) === 'S').length;
  console.log('自检: 解密成功，代码 ' + back.code.length + ' B / 池 ' + n + ' 项');
  const bad = Buffer.from(blob); bad[bad.length - 3] ^= 1;
  try { openV5(bad, master); console.log('自检: 篡改未被检出 ✗'); } catch (e) { console.log('自检: 篡改已被拒绝 ✓'); }
}