// tools/lib 的纯函数单测（P2-4）：node tools/tests/lib.test.mjs
import assert from 'node:assert';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { sha256Hex, sha256File, verifyFileSha256, shortHex } from '../lib/sha256.mjs';
import { repoRoot, under, rel, readJson, ensureDirFor } from '../lib/paths.mjs';
import { joinUrl, builtinCandidates, candidatesFor, releaseBase, rawBase, CATALOG_FILE } from '../lib/update-source.mjs';

let pass = 0;
const ok = (name, fn) => { fn(); pass++; console.log('  PASS ' + name); };

ok('sha256Hex 与已知向量一致', () => {
  assert.strictEqual(sha256Hex('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});
ok('sha256File/verify/shortHex', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'p24-'));
  const f = path.join(dir, 'a.bin'); fs.writeFileSync(f, 'abc');
  assert.strictEqual(sha256File(f), sha256Hex('abc'));
  assert.strictEqual(verifyFileSha256(f, sha256Hex('abc')).ok, true);
  assert.strictEqual(verifyFileSha256(f, 'deadbeef').ok, false);
  assert.strictEqual(shortHex('0123456789abcdefXYZ'), '0123456789abcdef');
});
ok('paths：under/rel/readJson(BOM)/ensureDirFor', () => {
  const root = repoRoot('/x/y');
  assert.strictEqual(under(root, 'tools', 'lib'), path.join('/x/y', 'tools', 'lib'));
  assert.strictEqual(rel('/x/y', '/x/y/tools/lib'), 'tools/lib');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'p24j-'));
  const f = path.join(dir, 'deep', 'j.json'); ensureDirFor(f);
  fs.writeFileSync(f, '\uFEFF{"a":1}');
  assert.deepStrictEqual(readJson(f), { a: 1 });
});
ok('update-source：候选链顺序固定、自建源优先、joinUrl 补斜杠', () => {
  const cs = builtinCandidates(CATALOG_FILE);
  assert.deepStrictEqual(cs.map((c) => c.source), ['builtin-release', 'builtin-raw', 'builtin-direct']);
  assert.ok(cs[0].url.endsWith('/releases/latest/download/plugins.json'));
  assert.ok(cs[1].url.includes('/plugin-release/plugin-release/plugins.json'));
  assert.strictEqual(joinUrl('https://h/p', 'a.apk'), 'https://h/p/a.apk');
  assert.strictEqual(candidatesFor('a.apk', 'https://lan/updates')[0].source, 'custom');
});

ok('update-source：releaseBase/rawBase 与既有字面量逐字一致（P2-4 第①批的钉子）', () => {
  assert.strictEqual(releaseBase('Jdkshen', 'ai.js-pro'),
      'https://github.com/Jdkshen/ai.js-pro/releases/latest/download/');
  assert.strictEqual(rawBase('Jdkshen', 'ai.js-pro', 'plugin-release'),
      'https://raw.githubusercontent.com/Jdkshen/ai.js-pro/plugin-release/plugin-release/');
  assert.ok(rawBase('Jdkshen', 'ai.js-pro', 'plugin-release', true)
      .startsWith('https://gh-proxy.com/https://raw.githubusercontent.com/Jdkshen/ai.js-pro/'));
});
console.log('OK (tools/lib) ' + pass + ' 项');