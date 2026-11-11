#!/usr/bin/env node
/**
 * 一条命令发布插件到公网（无需 token：用 SSH 推一个只放资产的 git 分支）。
 *
 * 用法：
 *   node tools/plugin-release.mjs                 # 构建 3 个插件 release + 生成 plugins.json + 推公网分支 + 校验 URL
 *   node tools/plugin-release.mjs --skip-build    # 跳过 gradle（用已有 APK）
 *   node tools/plugin-release.mjs --no-push       # 只在本地准备（不推公网）
 *   node tools/plugin-release.mjs --branch my-br  # 换发布分支（默认 plugin-release）
 *
 * 签名：设了 AIJSPRO_KEYSTORE/AIJSPRO_STORE_PASSWORD/AIJSPRO_KEY_ALIAS/AIJSPRO_KEY_PASSWORD 就用发布签名；
 *       没设则插件模块回退 debug 签名（**仅供本地/内测，勿对外**）。
 * 顺序：先三个 APK，最后 plugins.json（客户端按 file 字段名下载）。
 */
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const args = new Set(process.argv.slice(2));
const getArg = (name, def) => { const a = process.argv.find(x => x.startsWith('--' + name + '=')); return a ? a.split('=')[1] : def; };
const branch = getArg('branch', 'plugin-release');
const repoRoot = process.cwd();
const staging = join(repoRoot, '.artifacts', 'plugin-release');
const files = ['ocr-plugin.apk', 'xiangqi-plugin.apk', 'apkbuilder-plugin.apk', 'plugins.json'];
const run = (cmd, cmdArgs, opts = {}) => execFileSync(cmd, cmdArgs, { stdio: 'inherit', cwd: repoRoot, ...opts });
const capture = (cmd, cmdArgs, opts = {}) => execFileSync(cmd, cmdArgs, { encoding: 'utf8', cwd: repoRoot, ...opts }).trim();

function buildPlugins() {
  const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  console.log('[1/5] 构建三个插件 release …');
  run(gradlew, [':ocr-plugin:assembleRelease', ':xiangqi-plugin:assembleRelease', ':apkbuilder-plugin:assembleRelease', '--console=plain'], { shell: process.platform === 'win32' });
}

function stage() {
  console.log('[2/5] 归集到 ' + staging + ' …');
  mkdirSync(staging, { recursive: true });
  const map = {
    'ocr-plugin.apk': 'plugins/ocr-plugin/build/outputs/apk/release/ocr-plugin-release.apk',
    'xiangqi-plugin.apk': 'plugins/xiangqi-plugin/build/outputs/apk/release/xiangqi-plugin-release.apk',
    'apkbuilder-plugin.apk': 'plugins/apkbuilder-plugin/build/outputs/apk/release/apkbuilder-plugin-release.apk',
  };
  for (const [name, src] of Object.entries(map)) {
    if (!existsSync(src)) { console.error('缺构建产物：' + src); process.exit(1); }
    copyFileSync(src, join(staging, name));
  }
}

function manifest() {
  console.log('[3/5] 生成并校验 plugins.json …');
  run(process.execPath, ['tools/plugins-manifest.mjs', '--out', join(staging, 'plugins.json')]);
  run(process.execPath, ['tools/plugins-manifest.mjs', '--check', join(staging, 'plugins.json'), '--apk-dir', staging]);
  const m = JSON.parse(readFileSync(join(staging, 'plugins.json'), 'utf8'));
  for (const p of m.plugins) console.log('    ' + p.id + ' v' + p.version + '  ' + p.file + '  ' + p.sizeBytes + ' B  sha256=' + String(p.sha256).slice(0, 16) + '…');
  return m;
}

function publish() {
  console.log('[4/5] 推公网分支 ' + branch + '（只含资产，不动 main）…');
  const work = join(tmpdir(), 'aijspro-plugin-release-' + Date.now());
  rmSync(work, { recursive: true, force: true });
  try {
    run('git', ['fetch', 'origin', branch]);
    run('git', ['worktree', 'add', '-f', work, 'origin/' + branch]);
  } catch {
    console.log('    远端没有 ' + branch + '，从 origin/main 新建 …');
    run('git', ['worktree', 'add', '-f', '-b', branch, work, 'origin/main']);
  }
  const dir = join(work, 'plugin-release');
  mkdirSync(dir, { recursive: true });
  for (const f of files) copyFileSync(join(staging, f), join(dir, f));
  execFileSync('git', ['add', '-f', 'plugin-release'], { cwd: work, stdio: 'inherit' });
  const msg = 'release(plugins): ' + new Date().toISOString() + ' 插件资产（3 APK + plugins.json）';
  try { execFileSync('git', ['commit', '-m', msg], { cwd: work, stdio: 'inherit' }); } catch { console.log('    无变化，跳过提交'); }
  execFileSync('git', ['push', 'origin', 'HEAD:' + branch], { cwd: work, stdio: 'inherit' });
  run('git', ['worktree', 'remove', '--force', work]);
  console.log('    已推送 ✓');
}

async function verify() {
  console.log('[5/5] 校验公网 URL（可能需要几十秒生效）…');
  const prefix = 'https://raw.githubusercontent.com/Jdkshen/ai.js-pro/' + branch + '/plugin-release/';
  for (const f of files) {
    let ok = false;
    for (let i = 0; i < 6 && !ok; i++) {
      try {
        const res = await fetch(prefix + f, { method: 'GET' });
        ok = res.status === 200;
        console.log('    ' + res.status + '  ' + prefix + f);
      } catch (e) { console.log('    (重试) ' + e.message); }
      if (!ok) await new Promise(r => setTimeout(r, 5000));
    }
    if (!ok) console.error('    ✗ ' + f + ' 未能通过校验');
  }
}

(async () => {
  if (!args.has('--skip-build')) buildPlugins(); else console.log('[1/5] 跳过构建（--skip-build）');
  if (!args.has('--skip-build')) stage(); else stage();
  manifest();
  if (args.has('--no-push')) console.log('[4/5] 跳过推送（--no-push）；文件在 ' + staging);
  else publish();
  if (!args.has('--no-push')) await verify();
  console.log('完成。客户端零配置（更新源留空）即会走内置链：Release → raw 分支 → 直连。');
})().catch(e => { console.error('失败：', e.message); process.exit(1); });