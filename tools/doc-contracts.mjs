#!/usr/bin/env node
// 契约型文档门禁 CLI（P1-3）。纯逻辑在 doc-contracts-core.mjs（便于单测）。
//
// 用法：
//   node tools/doc-contracts.mjs --check                  # 校验全部契约（正向 + 反向断言）
//   node tools/doc-contracts.mjs --check --doc <path>      # 换文档（「故意改错 ⇒ 必须红」的对照）
//   node tools/doc-contracts.mjs --check --contract <id>   # 只跑某个契约
//   node tools/doc-contracts.mjs --list                    # 列出契约
import process from 'node:process';
import path from 'node:path';
import { CONTRACTS, evaluate, formatFailure, readText } from './doc-contracts-core.mjs';

const ROOT = process.cwd();

function parseArgs(argv) {
  const out = { mode: null, doc: null, contract: null };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--check') out.mode = 'check';
    else if (a === '--list') out.mode = 'list';
    else if (a === '--doc') out.doc = argv[++i];
    else if (a === '--contract') out.contract = argv[++i];
  }
  return out;
}

function run() {
  const args = parseArgs(process.argv);
  if (args.mode === 'list') {
    for (const c of CONTRACTS) console.log(c.id + '  ' + c.title + '  [' + c.doc + ']');
    return 0;
  }
  if (args.mode !== 'check') {
    console.log('用法：node tools/doc-contracts.mjs --check [--doc <path>] [--contract <id>] | --list');
    return 0;
  }
  const list = args.contract ? CONTRACTS.filter((c) => c.id === args.contract) : CONTRACTS;
  if (list.length === 0) { console.error('[doc-contracts] 没有匹配的契约：' + args.contract); return 2; }
  let failed = 0;
  for (const c of list) {
    let code, doc;
    try {
      code = readText(c.source, ROOT);
      doc = readText(args.doc || c.doc, ROOT);
    } catch (e) { console.error('[doc-contracts] ' + e.message); return 2; }
    const res = evaluate(c, code, doc);
    const name = c.id + (args.doc ? ' (doc=' + path.basename(args.doc) + ')' : '');
    if (res.failures.length === 0) {
      console.log('OK   ' + name + '：' + res.total + ' 条断言全部命中（正向 ' + res.positives + ' / 反向 ' + res.negatives + '）');
    } else {
      failed++;
      console.error('FAIL ' + name + '：' + res.failures.length + '/' + res.total + ' 条不命中');
      for (const f of res.failures) console.error(formatFailure(f));
    }
  }
  if (failed > 0) { console.error('[doc-contracts] 结论：FAIL（' + failed + ' 个契约与代码不一致）'); return 1; }
  console.log('[doc-contracts] 结论：PASS（文档与代码常量一致，含反向断言）');
  return 0;
}

process.exit(run());
