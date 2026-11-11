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
if (pairs.length === 0 && args.length >= 2) pairs.push(['fg/bg', args[0], args[1]]);
if (pairs.length === 0) {
  console.error('用法：node tools/ui-contrast.mjs <fgHex> <bgHex>  或  --pair "标签" <fgHex> <bgHex>');
  process.exit(2);
}
let bad = 0;
for (const [label, fg, bg] of pairs) {
  const r = ratio(fg, bg);
  const okBody = r >= 4.5, okLarge = r >= 3;
  if (!okLarge) bad++;
  console.log(`${label}: ${fg} on ${bg} = ${r.toFixed(2)}:1  ${okBody ? 'PASS(正文)' : okLarge ? 'PASS(仅大字号)' : 'FAIL'}`);
}
process.exit(bad > 0 ? 1 : 0);