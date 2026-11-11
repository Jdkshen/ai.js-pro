#!/usr/bin/env node
/**
 * PowerShell 编码门禁：**每个 .ps1 必须带 UTF-8 BOM**。
 *
 * 为什么需要它（三次真事故的教训）：本仓库的 .ps1 里全是中文注释与中文提示，而
 * PowerShell 5.1 在**没有 BOM** 时按 ANSI(GBK) 解析 ⇒ 中文变乱码 ⇒ `Parser::ParseFile` 报一
 * 批「意外的标记」语法错（实测 26–32 个错），而 `git diff` 里只看到「几行改动」根本看不出来 ✗。
 * 触发方式极其隐蔽：**用编辑工具改写 .ps1 会静默丢掉 BOM**（实测三次）。
 *
 * 用法：node tools/check-ps1-encoding.mjs [--dir tools] [--quiet]
 * 退出码：0 = 全部带 BOM；1 = 有文件缺 BOM（列出路径）。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
const args = process.argv.slice(2);
const quiet = args.includes('--quiet');
const dirIdx = args.indexOf('--dir');
const roots = dirIdx >= 0 ? [args[dirIdx + 1]] : ['tools', 'apps', 'modules', '.'];
// vendored/第三方目录不动（改了会与上游 diff 冲突）——它们多为纯 ASCII，且不属于我们的构建链。
const skip = new Set(['node_modules', 'build', '.git', '.gradle', '.artifacts', 'output', 'third_party',
  'third-party', 'reverse-native', '.venv', 'venv', 'env', 'site-packages']);
function walk(d, out = []) {
  let entries;
  try { entries = readdirSync(d, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    if (skip.has(e.name)) continue;
    const p = join(d, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.toLowerCase().endsWith('.ps1')) out.push(p);
  }
  return out;
}
const files = [...new Set(roots.flatMap((r) => { try { return statSync(r).isDirectory() ? walk(r) : [r]; } catch { return []; } }))];
const missing = [];
const bad = [];
for (const f of files) {
  const b = readFileSync(f);
  const hasBom = b.length >= 3 && b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf;
  if (!hasBom) missing.push(relative(process.cwd(), f));
  else if (b.includes(0xef) === false && false) bad.push(f);
}
if (!quiet) console.log(`ps1 文件 ${files.length} 个，缺 BOM ${missing.length} 个`);
if (missing.length) {
  console.error('FAIL: 以下 .ps1 缺 UTF-8 BOM（PowerShell 5.1 会按 GBK 解析中文 ⇒ 语法错）：');
  for (const m of missing) console.error('  - ' + m);
  console.error('修法：把 BOM 加回文件开头（EF BB BF），并跑 Parser::ParseFile 确认 0 错。');
  process.exit(1);
}
if (!quiet) console.log('PASS: 所有 .ps1 都带 UTF-8 BOM');