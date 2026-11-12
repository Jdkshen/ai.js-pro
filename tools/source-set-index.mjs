#!/usr/bin/env node
// 源集同名类索引 + 同名检查（P1-4，2026-11-12 W3）。
//
// 口径（Definition A，可复算）：
//   根 = apps/app/src/main · apps/app/src/miuix · apps/app/src/flutterpoc · apps/inrt/src/main · modules/**
//   比较单位 = **文件 basename**（.kt/.java 一起算）；同一 basename 出现在 >=2 个文件即「碰撞」。
//   分类：GEN = 路径含 /build/（生成物）；TEST = /src/test/ 或 /src/androidTest；其余 = SRC。
//   （Lead 的口径 B 更窄：只比「apps 各源集 vs modules」⇒ 得 6 处；两者差在 GEN/TEST 与 modules 内部对撞，见索引文档。）
//
// 用法：
//   node tools/source-set-index.mjs --list                    # 打印全部碰撞（name kind path）
//   node tools/source-set-index.mjs --check                   # 与基线比对：新碰撞 => exit 1
//   node tools/source-set-index.mjs --check --baseline <path>  # 换基线（用于负例对照）
//   node tools/source-set-index.mjs --write-baseline [path]    # 冻结当前碰撞（默认 tools/source-set-index.baseline.json）
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const ROOT = process.cwd();
// 扫描域（**这是本工具的定义**，改它必须同步 §docs/guides/源集与同名类索引.md §1 的范围声明）：
//   apps/** 与 modules/** 下的 `src/**` 与 `build/generated/**`（其余 build/ 中间产物不扫）
const ROOTS = ['apps', 'modules'];
const INCLUDE = /\/src\/|\/build\/generated\//;
const SKIP = /(\/node_modules\/|\/\.gradle\/|\/\.cxx\/|\/\.git\/|\/build\/(intermediates|tmp|outputs|reports|test-results)\b)/;
const DEFAULT_BASELINE = 'tools/source-set-index.baseline.json';

function classify(rel) {
  if (rel.includes('/build/')) return 'GEN';
  if (rel.includes('/src/test/') || rel.includes('/src/androidTest')) return 'TEST';
  return 'SRC';
}

function walk(dir, out) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    const rel = '/' + path.relative(ROOT, p).split(path.sep).join('/');
    if (SKIP.test(rel)) continue;
    if (e.isDirectory()) { walk(p, out); continue; }
    if (!(e.name.endsWith('.kt') || e.name.endsWith('.java'))) continue;
    if (!INCLUDE.test(rel)) continue;
    out.push(p);
  }
}

function collisions() {
  const byName = new Map();
  for (const r of ROOTS) {
    const base = path.join(ROOT, r);
    const files = [];
    walk(base, files);
    for (const f of files) {
      const rel = path.relative(ROOT, f).split(path.sep).join('/');
      const name = path.basename(f);
      if (!byName.has(name)) byName.set(name, []);
      byName.get(name).push(rel);
    }
  }
  const out = [];
  for (const [name, files] of [...byName.entries()].sort()) {
    if (files.length < 2) continue;
    const kinds = new Set(files.map(classify));
    out.push({ name, kind: kinds.size === 1 ? [...kinds][0] : 'MIX', files: files.sort() });
  }
  return out;
}

function parseArgs(argv) {
  const out = { mode: null, baseline: DEFAULT_BASELINE };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--list') out.mode = 'list';
    else if (a === '--check') out.mode = 'check';
    else if (a === '--write-baseline') { out.mode = 'write'; if (argv[i + 1] && !argv[i + 1].startsWith('--')) out.baseline = argv[++i]; }
    else if (a === '--baseline') out.baseline = argv[++i];
  }
  return out;
}

function run() {
  const args = parseArgs(process.argv);
  const cur = collisions();
  if (args.mode === 'list') {
    for (const c of cur) console.log(c.name + '\t' + c.kind + '\t' + c.files.join(' | '));
    const byKind = cur.reduce((m, c) => (m[c.kind] = (m[c.kind] || 0) + 1, m), {});
    console.log('# total=' + cur.length + ' SRC=' + (byKind.SRC || 0) + ' TEST=' + (byKind.TEST || 0) + ' GEN=' + (byKind.GEN || 0) + ' MIX=' + (byKind.MIX || 0));
    return 0;
  }
  if (args.mode === 'write') {
    const body = {
      generatedAt: new Date().toISOString(),
      definition: 'basename collisions across ' + ROOTS.join(' + ') + ' (GEN/TEST classified)',
      collisions: cur,
    };
    const outPath = path.isAbsolute(args.baseline) ? args.baseline : path.join(ROOT, args.baseline);
    fs.writeFileSync(outPath, JSON.stringify(body, null, 2) + '\n', 'utf8');
    console.log('[source-set-index] 已写基线 ' + args.baseline + '（' + cur.length + ' 条）');
    return 0;
  }
  if (args.mode === 'check') {
    const bp = path.isAbsolute(args.baseline) ? args.baseline : path.join(ROOT, args.baseline);
    if (!fs.existsSync(bp)) { console.error('[source-set-index] 基线不存在：' + bp + '（先跑 --write-baseline）'); return 2; }
    let base;
    try {
      base = JSON.parse(fs.readFileSync(bp, 'utf8'));
    } catch (e) {
      console.error('[source-set-index] 基线文件不是合法 JSON：' + bp);
      console.error('   （解析错误：' + e.message + '）');
      return 2;
    }
    if (!base || !Array.isArray(base.collisions)) {
      console.error('[source-set-index] 基线结构不对：缺 collisions[] 数组 ⇒ ' + bp);
      return 2;
    }
    const known = new Map(base.collisions.map((c) => [c.name, new Set(c.files)]));
    const problems = [];
    const gone = [];
    for (const c of cur) {
      const prev = known.get(c.name);
      if (!prev) { problems.push({ name: c.name, kind: c.kind, why: '新碰撞（基线里没有这个 basename）', files: c.files }); continue; }
      const added = c.files.filter((f) => !prev.has(f));
      if (added.length) problems.push({ name: c.name, kind: c.kind, why: '已知 basename 出现了新文件', files: added });
    }
    for (const [name, files] of known) {
      if (!cur.some((c) => c.name === name)) gone.push({ name, files: [...files] });
    }
    if (gone.length) {
      console.log('WARN 基线里这些碰撞已消失（基线过时，建议 --write-baseline 重冻结）：' + gone.map((g) => g.name).join(', '));
    }
    if (problems.length) {
      console.error('FAIL 检出 ' + problems.length + ' 处新的同名碰撞：');
      for (const p of problems) {
        console.error('   - ' + p.name + ' [' + p.kind + '] ' + p.why);
        for (const f of p.files) console.error('       ' + f);
      }
      console.error('[source-set-index] 结论：FAIL（新同名类要么改前缀，要么显式重冻结基线）');
      return 1;
    }
    console.log('[source-set-index] 结论：PASS（当前 ' + cur.length + ' 处碰撞与基线一致）');
    return 0;
  }
  console.log('用法：node tools/source-set-index.mjs --list | --check [--baseline <p>] | --write-baseline [p]');
  return 0;
}

process.exit(run());
