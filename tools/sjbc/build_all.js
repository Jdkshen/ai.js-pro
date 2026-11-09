// build_all.js —— 手机内一键：把脚本目录里所有 .src 编译 + 加密，并投放给宿主
// 直接改下面 4 行配置即可（不需要命令行参数）
const CFG = {
  srcDir:    '/storage/emulated/0/脚本/sjbc-tools/samples',        // 你的脚本目录
  outDir:    '/storage/emulated/0/脚本/sjbc-tools/out',            // 产出目录
  certSha:   '80b26b41168e41fc6add6e863f9dc8770956e99ab6b830320fb360ceb9ad40ea',  // 签名证书 SHA-256
  deployTo:  '/sdcard/Android/data/com.aijs.sjbc.demo/files/modules',             // 热加载目录（留空则不投放）
};
const fs = require('fs');
const path = require('path');
const { compile } = require('/storage/emulated/0/脚本/sjbc-tools/ajs2sjbc.js');
const { buildV5, openV5 } = require('/storage/emulated/0/脚本/sjbc-tools/pack_v5.js');
const { masterFromCert } = require('/storage/emulated/0/脚本/sjbc-tools/keyderiv.js');

const HOST = ['print', 'len', 'push', 'slice0'];
const NL = String.fromCharCode(10);
const master = masterFromCert(CFG.certSha);
fs.mkdirSync(CFG.outDir, { recursive: true });
if (CFG.deployTo) { try { fs.mkdirSync(CFG.deployTo, { recursive: true }); } catch (e) {} }

const files = fs.readdirSync(CFG.srcDir).filter(f => f.endsWith('.src'));
console.log('发现脚本 ' + files.length + ' 个');
const manifest = [];
for (const f of files) {
  const name = f.replace(/\.src$/, '');
  const src = fs.readFileSync(path.join(CFG.srcDir, f), 'utf8');
  const t0 = Date.now();
  const c = compile(src, HOST);
  const blob = buildV5({ bytes: c.bytes, pool: c.pool, entries: c.entries }, master);
  const out = path.join(CFG.outDir, name + '.sjbc');
  fs.writeFileSync(out, blob);
  let ok = '—';
  try { const b = openV5(blob, master); ok = '解 ' + b.code.length + 'B'; } catch (e) { ok = '自检失败 ✗'; }
  let deployed = '';
  if (CFG.deployTo) { try { fs.writeFileSync(path.join(CFG.deployTo, name + '.sjbc'), blob); deployed = ' → 已投放'; } catch (e) { deployed = ' → 投放失败'; } }
  manifest.push({ name: name, asset: 'sjbc/' + name + '.sjbc', bytes: c.bytes.length, cipher: blob.length, funcs: c.chunks });
  console.log('  ' + name + ': 字节码 ' + c.bytes.length + 'B / 函数 ' + c.chunks + ' / 密文 ' + blob.length + 'B / ' + ok + ' (' + (Date.now() - t0) + 'ms)' + deployed);
}
fs.writeFileSync(path.join(CFG.outDir, 'manifest.json'), JSON.stringify({ version: 1, cert: CFG.certSha, modules: manifest }, null, 2));
console.log('清单已写: ' + path.join(CFG.outDir, 'manifest.json'));