#!/usr/bin/env node
/**
 * WCAG 对比度计算器（团队共用的可复算口径）。
 *
 * 用法：
 *   node tools/ui-contrast.mjs ffffff fd999a
 *   node tools/ui-contrast.mjs --pair "白字/粉底" ffffff fd999a
 *
 * 判据：正文 ≥ 4.5:1；大字号（≥18px 或粗体 ≥14px）≥ 3:1。
 */
const [, , ...args] = process.argv;
function lum(hex) {
  const h = hex.replace('#', '');
  const ch = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
  const f = (c) => (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
  return 0.2126 * f(ch[0]) + 0.7152 * f(ch[1]) + 0.0722 * f(ch[2]);
}
function ratio(a, b) {
  const la = lum(a), lb = lum(b);
  const hi = Math.max(la, lb), lo = Math.min(la, lb);
  return (hi + 0.05) / (lo + 0.05);
}
const pairs = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--pair') { pairs.push([args[++i], args[++i], args[++i]]); }
}
if (pairs.length === 0 && args.length >= 2 && !args.includes('--audit')) pairs.push(['fg/bg', args[0], args[1]]);
// 注意：--audit 模式没有 pair 参数，不能在这里提前退出（否则永远走不到下面的分派）。
if (pairs.length === 0 && !args.includes('--audit')) {
  console.error('用法：node tools/ui-contrast.mjs <fgHex> <bgHex>  或  --pair "标签" <fgHex> <bgHex>  或  --audit');
  process.exit(2);
}
let bad = 0;
for (const [label, fg, bg] of pairs) {
  const r = ratio(fg, bg);
  const okBody = r >= 4.5, okLarge = r >= 3;
  if (!okLarge) bad++;
  console.log(`${label}: ${fg} on ${bg} = ${r.toFixed(2)}:1  ${okBody ? 'PASS(正文)' : okLarge ? 'PASS(仅大字号)' : 'FAIL'}`);
}
// 退出延后到分派结束（见文件末尾）

// ─────────────────────────────────────────────────────────────

// --audit：扫描 flutter-ui/lib/** 里**硬编码**颜色字面量，算它相对
//   「浅色主题表面 #FFFFFF」与「深色主题表面 #1C2022」的对比度。
//
// 口径与边界（重要，别把它当全部）：
//  · 只查**字面量**（Color(0xRRGGBB) / Colors.xxx）；scheme.* 派生色**算不了**（需运行时配色）。
//  · 判据用**非文本**门槛 3:1（WCAG 1.4.11，适用于图标/边框/图形）；
//    当**正文文字**用时要求 4.5:1 ⇒ 本工具只报 3:1 以下的「确定有问题」，4.5 以下另标提示。
//  · 装饰性/背景色（透明叠加、渐变端）本工具会一并列出，需人工判断是否真的承载信息。
//  · **已知误报类**（看到就先人工判断，别直接改；本工具用的是括号深度启发式，不是真解析）：
//      1) **具名调色板**：`static const dark = _Palette(...)` / `light = _Palette(...)`
//         （如 utility_pages.dart 的 console 调色板）—— 由更高层整体切换，单看某个色值两边都不达标；
//      2) **三元表达式的 else 分支**：`dark ? const { A } : const { B }` 里 B 所在行的开括号
//         是 `: const {`，行内没有主题词 ⇒ 会被报出；
//      3) **彩色背景上的文字色**：如抽屉头部青绿渐变上的白字、task_page 头像上的字
//         —— 它的比较对象是那块彩色底，不是主题表面；
//      4) 渐变端点、装饰性描边等不承载信息的色值。
//  · 已知**假阴性**（少报）：位于主题分支内、但外层表达式写法特殊的色值可能被跳过。
//  ⇒ 结论：本工具是**分诊提示**，默认 exit 0；只有 --strict 才当门禁。
//     **真正的门禁是 flutter-ui/test/contrast_test.dart 的数值断言**（精确、可复算、已纳入 flutter test）。
//
// 用法：node tools/ui-contrast.mjs --audit [--dir <path>]
/**
 * 判断第 i 行的色值是否位于「主题分支」表达式内。
 * 逐个字符累计括号深度，向前找最近的未闭合开括号；那一行或其上一行提到主题相关词就算。
 */
function insideThemeBranch(lines, i) {
  const themed = (s) => /dark|brightness|Theme\.of|scheme\./.test(s);
  if (themed(lines[i])) return true;
  let depth = 0;
  for (let k = i; k >= 0 && i - k <= 40; k--) {
    const line = lines[k];
    // 本行（i 行自身只算色值之后的字符，避免把自己的括号算进去）
    const scan = k === i ? line.slice(0, line.indexOf('Color(0x')) : line;
    for (let c = scan.length - 1; c >= 0; c--) {
      const ch = scan[c];
      if (ch === ')' || ch === ']' || ch === '}') depth++;
      else if (ch === '(' || ch === '[' || ch === '{') {
        if (depth > 0) depth--;
        else {
          // 找到最近的开括号 ⇒ 看它所在行与上一行是否说明主题
          return themed(line) || (k > 0 && themed(lines[k - 1]));
        }
      }
    }
  }
  return false;
}

async function runAudit() {
  const fsmod = await import('node:fs');
  const pathmod = await import('node:path');
  const dirIdx = args.indexOf('--dir');
  const root = dirIdx >= 0 ? args[dirIdx + 1] : 'flutter-ui/lib';
  const LIGHT = 'ffffff', DARK = '1c2022';
  const walk = (d) => fsmod.readdirSync(d, { withFileTypes: true }).flatMap((e) => {
    const p = pathmod.join(d, e.name);
    return e.isDirectory() ? walk(p) : (e.name.endsWith('.dart') ? [p] : []);
  });
  const files = fsmod.existsSync(root) ? walk(root) : [];
  if (files.length === 0) { console.error('未找到 dart 文件：' + root); process.exit(2); }
  const rows = [];
  let skippedThemed = 0;
  for (const f of files) {
    const lines = fsmod.readFileSync(f, 'utf8').split(/\r?\n/);
    lines.forEach((line, i) => {
      for (const m of line.matchAll(/Color\(0x([0-9a-fA-F]{6,8})\)/g)) {
        const raw = m[1];
        const hex = raw.length === 8 ? raw.slice(2) : raw;
        const alpha = raw.length === 8 ? parseInt(raw.slice(0, 2), 16) / 255 : 1;
        if (alpha < 0.5) continue; // 半透明叠加算不准，跳过
        // 关键：**已按主题分支写的**（dark ? a : b / Theme.of / scheme.*）不算问题 ——
        // 每个分支只服务它自己的主题，拿两个表面都去比必然误报。
        // 判定「是否位于主题分支内」用**括号深度**而不是固定行窗：
        //   · 行窗太窄 ⇒ 多行集合（dark ? { 多个色值 } : { … }）漏判；
        //   · 行窗太宽 ⇒ 把相邻的无关语句也算进去（假阴性，测试抓到过）。
        // 做法：从该色值向前找**最近的未闭合开括号**，看那一行（及上一行）是否提到 dark/主题。
        if (insideThemeBranch(lines, i)) { skippedThemed++; continue; }
        rows.push({ file: pathmod.basename(f), line: i + 1, hex, light: ratio(hex, LIGHT), dark: ratio(hex, DARK) });
      }
    });
  }
  console.log('（已跳过 ' + skippedThemed + ' 个带主题分支的色值：它们各自只服务一种主题）');
  const bad = rows.filter((r) => r.light < 3 || r.dark < 3);
  const warn = rows.filter((r) => !(r.light < 3 || r.dark < 3) && (r.light < 4.5 || r.dark < 4.5));
  console.log(`已扫描 ${files.length} 个 dart 文件、${rows.length} 个不透明硬编码色`);
  console.log(`\n=== 低于 3:1（确定不达标，需处理）: ${bad.length} ===`);
  for (const r of bad) console.log(`  ${r.file}:${r.line}  #${r.hex}  浅底 ${r.light.toFixed(2)}:1  深底 ${r.dark.toFixed(2)}:1`);
  console.log(`\n=== 3:1–4.5:1（当正文文字用则不足，图标可用）: ${warn.length} ===`);
  for (const r of warn) console.log(`  ${r.file}:${r.line}  #${r.hex}  浅底 ${r.light.toFixed(2)}:1  深底 ${r.dark.toFixed(2)}:1`);
  // 默认是**分诊提示**（exit 0）；只有显式 --strict 才当门禁失败。
  // 原因：本工具只查字面量、判不了「该色用在图标还是正文」，误报会挡住正常出包。
  process.exit(args.includes('--strict') && bad.length > 0 ? 1 : 0);
}

// ── 分派：--audit 优先；否则按 pair 模式 ──
if (args.includes('--audit')) {
  await runAudit();
} else {
  process.exit(bad > 0 ? 1 : 0);
}