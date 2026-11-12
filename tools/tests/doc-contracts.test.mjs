// tools/tests/doc-contracts.test.mjs —— 契约文档门禁的**内存级**单测（P1-3 补强，W2 复跑缺陷的正解）
//
// 为什么要有它：W2 用「复制真文档 + PowerShell 改错」复跑时，PS 5.1 的 ANSI 读取把整篇中文弄坏 ⇒ 9 条全红，
//   **「只错一处能否恰好点名一条」这条没验证成**。本测试把文档文本当**变量**、只在内存里改一处，
//   断言「**恰好 1 条**不命中 + 该条的 what 出现在格式化输出里」，并且**只读真实文件、不写任何东西**。
//
// 覆盖：
//   0) 真文档 ⇒ 0 失败
//   A) 默认 1 → 默认 2               ⇒ 恰好 1 条（正向 missing），点名「默认等级 = 1」
//   B) 加密 so 的 native → assets     ⇒ 恰好 1 条（正向 missing），点名该档位行
//   C) 规则区注入「被推成/降级为」    ⇒ 恰好 1 条（**反向** forbidden），带行号
//   D) 同一句放到 §1.1 **作用域之外**  ⇒ 0 失败（证明反向断言**限定了作用域**）
//   E) 同一句作为规则行但带「历史」标记 ⇒ 0 失败（证明历史语境**豁免**）
//   F) 表格等级 2 → 3                ⇒ **2** 条（正向 missing + 反向 forbidden）——同一次编辑合法地触发两条
import assert from 'node:assert/strict';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CONTRACTS, evaluate, formatFailure, readText } from '../doc-contracts-core.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const contract = CONTRACTS[0];
const code = readText(contract.source, ROOT);
const doc = readText(contract.doc, ROOT);

let pass = 0;
const failures = [];
function check(name, fn) {
  try { fn(); pass++; console.log('PASS ' + name); }
  catch (e) { failures.push(name + ': ' + e.message); console.error('FAIL ' + name + ': ' + e.message); }
}

const run = (docText) => evaluate(contract, code, docText);

check('0) 真文档：0 失败（正向+反向全过）', () => {
  const res = run(doc);
  assert.equal(res.total, 12, '断言总数应为 12（正向 9 + 反向 3）');
  assert.equal(res.failures.length, 0, '真文档不应有失败：' + JSON.stringify(res.failures));
});

const MUT_A_FROM = '默认 1（保持历史行为';
const MUT_A_TO = '默认 2（保持历史行为';
check('A) 默认 1→2 ⇒ 恰好 1 条，且点名「默认等级 = 1」并出现在输出里', () => {
  assert.ok(doc.includes(MUT_A_FROM), '前置：真文档里应有「' + MUT_A_FROM + '」');
  const res = run(doc.replace(MUT_A_FROM, MUT_A_TO));
  assert.equal(res.failures.length, 1, '应恰好 1 条失败，实际 ' + res.failures.length);
  const f = res.failures[0];
  assert.equal(f.kind, 'missing');
  assert.ok(f.what.includes('默认等级 = 1'), 'what 应点名默认等级 = 1，实际：' + f.what);
  assert.ok(formatFailure(f).includes(f.what), '格式化输出必须包含 what');
});

const MUT_B_FROM = '| 加密 so | 1 | `native` |';
const MUT_B_TO = '| 加密 so | 1 | `assets` |';
check('B) 加密 so 的 native→assets ⇒ 恰好 1 条，点名该档位行', () => {
  assert.ok(doc.includes(MUT_B_FROM), '前置：真文档里应有该行');
  const res = run(doc.replace(MUT_B_FROM, MUT_B_TO));
  assert.equal(res.failures.length, 1, '应恰好 1 条失败，实际 ' + res.failures.length);
  assert.ok(res.failures[0].what.includes('档位行 加密 so'), 'what 应点名 加密 so 行，实际：' + res.failures[0].what);
});

const DEPRECATED = '5. 等级 2 + `native` 会被推成「加密 so」，再打包时编译就丢了。';
check('C) 规则区注入「被推成」（无历史标记）⇒ 恰好 1 条**反向**失败，带行号', () => {
  const marker = '4. `scriptStorage=native` 但等级是 0 时会被自动提到 1：嵌进原生库的内容必须是密文；';
  assert.ok(doc.includes(marker), '前置：真文档里应有第 4 条规则');
  const res = run(doc.replace(marker, marker + '\n' + DEPRECATED));
  assert.equal(res.failures.length, 1, '应恰好 1 条失败，实际 ' + res.failures.length + '：' + JSON.stringify(res.failures));
  const f = res.failures[0];
  assert.equal(f.kind, 'forbidden', '应为反向断言命中');
  assert.ok(f.what.includes('现行规则'), 'what 应说明「把降级当现行规则」，实际：' + f.what);
  assert.ok(Number.isInteger(f.line) && f.line > 0, '应带文档行号');
});

check('D) 同一句放到 §1.1 作用域之外 ⇒ 0 失败（证明反向断言**限定了作用域**）', () => {
  const outside = '## 1.2 假段落（作用域外）\n\n' + DEPRECATED + '\n';
  const res = run(doc + '\n' + outside);
  assert.equal(res.failures.length, 0, '作用域外不应被反向断言命中，实际：' + JSON.stringify(res.failures));
});

check('E) 同一句作为规则行但带「历史」标记 ⇒ 0 失败（证明历史语境豁免）', () => {
  const marker = '4. `scriptStorage=native` 但等级是 0 时会被自动提到 1：嵌进原生库的内容必须是密文；';
  const historical = '5. 历史：等级 2 + `native` 曾被推成「加密 so」，已修；';
  const res = run(doc.replace(marker, marker + '\n' + historical));
  assert.equal(res.failures.length, 0, '带历史标记的规则行应豁免，实际：' + JSON.stringify(res.failures));
});

check('F) 表格等级 2→3 ⇒ 2 条（正向 missing + 反向 forbidden）——同一次编辑合法地触发两条', () => {
  const from = '| 快照（编译） | 2 |';
  const res = run(doc.replace(from, '| 快照（编译） | 3 |'));
  assert.equal(res.failures.length, 2, '应 2 条，实际 ' + res.failures.length + '：' + JSON.stringify(res.failures));
  const kinds = res.failures.map((f) => f.kind).sort().join(',');
  assert.equal(kinds, 'forbidden,missing', '两条应分别是 missing 与 forbidden');
});

console.log('');
console.log('[doc-contracts.test] PASS=' + pass + ' FAIL=' + failures.length);
if (failures.length) { for (const f of failures) console.error('  - ' + f); process.exit(1); }
