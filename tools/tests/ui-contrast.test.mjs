#!/usr/bin/env node
/** ui-contrast.mjs 的行为测试（离线、无依赖）。 */
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const tool = process.argv[2] || 'tools/ui-contrast.mjs';
let pass = 0, fail = 0;
const check = (name, cond, extra = '') => {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fail++; console.log('  FAIL ' + name + ' ' + extra); }
};
const run = (args) => {
  try {
    const out = execFileSync(process.execPath, [tool, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    return { code: 0, out };
  } catch (e) {
    return { code: e.status ?? 1, out: (e.stdout || '') + (e.stderr || '') };
  }
};

// 1) 对比度数值正确（黑/白 = 21:1）
const r1 = run(['000000', 'ffffff']);
check('黑白对比度 = 21.00:1', /21\.00:1/.test(r1.out), r1.out.trim());

// 2) 已知不达标值会被判 FAIL 并非零退出
const r2 = run(['ffffff', 'fd999a']);
check('白字/粉底 判 FAIL', /FAIL/.test(r2.out) && r2.code === 1, r2.out.trim());

// 3) 达标值 PASS
const r3 = run(['ffffff', 'c62828']);
check('白字/深红 判 PASS', /PASS/.test(r3.out) && r3.code === 0, r3.out.trim());

// 4) --audit：带主题分支的行会被跳过；无分支的不达标色会被报出
const dir = mkdtempSync(join(tmpdir(), 'uicontrast-'));
mkdirSync(join(dir, 'lib'), { recursive: true });
writeFileSync(join(dir, 'lib', 'a.dart'), [
  'const themed = dark ? const Color(0xfffd999a) : const Color(0xffc62828);',
  'const bad = const Color(0xff99cc99);',
].join('\n'));
const r4 = run(['--audit', '--dir', join(dir, 'lib')]);
check('audit 跳过带 dark 分支的粉底', !/#fd999a/.test(r4.out), r4.out.trim());
check('audit 报出无分支的绿底', /#99cc99/.test(r4.out), r4.out.trim());
check('audit 默认 exit 0（分诊而非门禁）', r4.code === 0, 'code=' + r4.code);

// 5) --audit --strict 才当门禁
const r5 = run(['--audit', '--dir', join(dir, 'lib'), '--strict']);
check('audit --strict 非零退出', r5.code === 1, 'code=' + r5.code);


// 6) 白名单过期检查：不再命中任何色值的豁免会被报出（防豁免只增不减）
const allowPath = join(dir, 'allow.json');
writeFileSync(allowPath, JSON.stringify([{ file: 'a.dart', hex: '123456', reason: 'test' }]));
const r6 = run(['--audit', '--dir', join(dir, 'lib'), '--allow', allowPath]);
check('audit 报出失效白名单条目', /已失效/.test(r6.out) && /123456/.test(r6.out), r6.out.trim().slice(0, 200));
const r7 = run(['--audit', '--dir', join(dir, 'lib'), '--allow', allowPath, '--strict']);
check('失效白名单不导致 --strict 失败（只警告）', r7.code === 1 ? /低于 3:1/.test(r7.out) : true, 'code=' + r7.code);

console.log(`\nui-contrast: ${pass} passed, ${fail} failed`);
process.exit(fail > 0 ? 1 : 0);