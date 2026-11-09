#!/usr/bin/env node
// sjbc-pack.mjs —— 一键：源码 → SJBC 字节码 → v5 加密容器（.sjbc）
// 用法：
//   node sjbc-pack.mjs <源码文件> <输出文件> --cert <证书SHA256|APK 路径>
//   node sjbc-pack.mjs samples/hello.src out/hello.sjbc --cert ./app.apk
//   node sjbc-pack.mjs samples/hello.src out/hello.sjbc --cert 80b2...40ea
//   （不加 --cert 则用占位证书；本地自测可 --cert self）
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { compile } = require('./ajs2sjbc.js');
const { buildV5, openV5 } = require('./pack_v5.js');
const { masterFromCert } = require('./keyderiv.js');
const { run } = require('./vm.js');

const HOST = ['print', 'len', 'push', 'slice0'];     // ★ 必须与宿主实现一致

function certShaFromApk(apkPath) {
  // 从 APK 里取签名证书指纹：优先用 apksigner，其次 keytool；都没有就报错提示手填
  const { execFileSync } = require('node:child_process');
  const tryCmd = (cmd, args) => { try { return execFileSync(cmd, args, { encoding: 'utf8' }); } catch { return null; } };
  let out = tryCmd('apksigner', ['verify', '--print-certs', apkPath]);
  if (out) { const m = out.match(/SHA-256 digest:\s*([0-9a-fA-F:]{64,})/); if (m) return m[1].replace(/:/g, '').toLowerCase(); }
  out = tryCmd('keytool', ['-printcert', '-jarfile', apkPath]);
  if (out) { const m = out.match(/SHA256:\s*([0-9A-F:]{64,})/); if (m) return m[1].replace(/:/g, '').toLowerCase(); }
  throw new Error('无法从 APK 读出证书指纹，请用 apksigner verify --print-certs 查出来，再用 --cert <64位hex> 传入');
}

const argv = process.argv.slice(2);
if (argv.length < 2) { console.error('用法: node sjbc-pack.mjs <源码> <输出.sjbc> [--cert <sha256|apk>] [--check]'); process.exit(2); }
const [srcPath, outPath] = argv;
const ci = argv.indexOf('--cert');
let certArg = ci >= 0 ? argv[ci + 1] : 'self';
const doCheck = argv.includes('--check');

let certSha;
if (certArg === 'self') certSha = '00'.repeat(32);
else if (/^[0-9a-fA-F]{64}$/.test(certArg)) certSha = certArg.toLowerCase();
else certSha = certShaFromApk(certArg);

const src = fs.readFileSync(srcPath, 'utf8');
const t0 = Date.now();
const c = compile(src, HOST);
const master = masterFromCert(certSha);
const blob = buildV5({ bytes: c.bytes, pool: c.pool, entries: c.entries }, master);
fs.mkdirSync(path.dirname(path.resolve(outPath)), { recursive: true });
fs.writeFileSync(outPath, blob);

console.log('源码      : ' + srcPath);
console.log('字节码    : ' + c.bytes.length + ' B / 函数 ' + c.chunks + ' 个 / 常量 ' + c.pool.length + ' 项');
console.log('密文      : ' + outPath + '  ' + blob.length + ' B');
console.log('证书指纹  : ' + (certArg === 'self' ? '(占位 self)' : certSha));
console.log('编译耗时  : ' + (Date.now() - t0) + ' ms');

if (doCheck) {
  const back = openV5(blob, master);          // 解密 + AEAD 校验（改一个字节就会抛）
  const lines2 = back.poolText.split(String.fromCharCode(10));
  const nPool = lines2.filter(l => l.startsWith("N ") || l.startsWith("S ")).length;
  console.log("自检      : 解密成功，代码 " + back.code.length + " B / 池 " + nPool + " 项");
  const bad = Buffer.from(blob); bad[bad.length - 3] ^= 1;
  try { openV5(bad, master); console.log("自检      : 篡改未被检出 ✗"); }
  catch { console.log("自检      : 篡改已被拒绝 ✓"); }
}