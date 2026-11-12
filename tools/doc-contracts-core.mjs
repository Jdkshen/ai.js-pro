// tools/doc-contracts-core.mjs —— 契约文档门禁的**纯逻辑核心**（可被 CLI 与单测共用）。
//
// 约定：契约 = { id, title, doc, source, scope: {start, end}, historyMarkers, build(codeText) -> { positives[], negatives[] } }
//   positives[] = { what, needle }                 // 文档**必须**含 needle
//   negatives[] = { what, test(docText) -> null|{line,text,detail} }  // 文档在**作用域内**不得命中
//
// 方向：代码是唯一真值来源（先抽常量），文档是人类可读镜像（再断言）。
//
// 待办（W2 保留意见，Lead 决定「先不改结构，记注释即可」）：当前有 4 条 needle 是**中文句子硬编码**，
//   下一批做新契约时建议给这类加 kind: 'doc-phrase' 以区分「硬编码短语」与「代码常量镜像」。
import fs from 'node:fs';
import path from 'node:path';

const SP = 'modules/engine/src/main/java/com/stardust/autojs/project/ScriptProtection.java';

function intConst(text, name) {
  const m = text.match(new RegExp('\\b' + name + '\\s*=\\s*(\\d+)\\s*;'));
  return m ? Number(m[1]) : null;
}
function strConst(text, name) {
  const m = text.match(new RegExp('\\b' + name + '\\s*=\\s*"([^"]*)"\\s*;'));
  return m ? m[1] : null;
}
function resolvedConst(text, name) {
  const m = text.match(new RegExp('\\b' + name + '\\s*=\\s*([A-Za-z0-9_]+)\\s*;'));
  if (!m) return null;
  const rhs = m[1];
  return /^\\d+$/.test(rhs) ? Number(rhs) : intConst(text, rhs);
}

// ---- 作用域工具：把文档切成「契约段」并按行号定位 ----
export function scopeResolved(docText, scope) {
  if (!scope || !scope.start) return true;
  return docText.split('\n').some((l) => l.includes(scope.start));
}
export function scopedLines(docText, scope) {
  const lines = docText.split('\n');
  let start = 0, end = lines.length;
  if (scope && scope.start) {
    const i = lines.findIndex((l) => l.includes(scope.start));
    if (i >= 0) start = i;
  }
  if (scope && scope.end) {
    for (let i = start + 1; i < lines.length; i++) { if (lines[i].startsWith(scope.end)) { end = i; break; } }
  }
  return lines.slice(start, end).map((text, i) => ({ line: start + i + 1, text }));
}

function isHistoryLine(text, markers) {
  return markers.some((m) => text.includes(m));
}

export const CONTRACTS = [
  {
    id: 'script-protection-levels',
    title: '脚本保护档位表（encryptLevel / scriptStorage）',
    doc: 'docs/PERMISSIONS_AND_RELEASE.md',
    source: SP,
    // 作用域：只查 §1.1 这一节（不碰 §1.2 之后的其它内容，避免误伤）
    scope: { start: '## 1.1 脚本 APK 的保护等级', end: '## ' },
    // 携带这些字样的行 = 明确的历史/更正语境 ⇒ 反向断言豁免
    historyMarkers: ['历史', '曾经', '以前', '已修', '更正中', 'bug'],
    build(codeText, ctx) {
      const none = intConst(codeText, 'LEVEL_NONE');
      const enc = intConst(codeText, 'LEVEL_ENCRYPT');
      const comp = intConst(codeText, 'LEVEL_COMPILE');
      const maxLevel = resolvedConst(codeText, 'MAX_LEVEL');
      const def = resolvedConst(codeText, 'DEFAULT_LEVEL');
      const assets = strConst(codeText, 'STORAGE_ASSETS');
      const native = strConst(codeText, 'STORAGE_NATIVE');
      const positives = [];
      if (maxLevel !== comp) {
        positives.push({ what: '(代码自洽) MAX_LEVEL 应等于 LEVEL_COMPILE', needle: 'CODE_SELFCHECK_MAX_LEVEL_' + maxLevel + '_' + comp });
      }
      const rows = [
        ['不加密', none, assets],
        ['加密（默认）', enc, assets],
        ['快照（编译）', comp, assets],
        ['加密 so', enc, native],
        ['快照 so', comp, native],
      ];
      for (const [label, level, storage] of rows) {
        positives.push({
          what: '档位行 ' + label + ' -> encryptLevel=' + level + ' / scriptStorage=' + storage,
          needle: '| ' + label + ' | ' + level + ' | `' + storage + '` |',
        });
      }
      positives.push({ what: '默认等级 = ' + def, needle: '默认 ' + def + '（保持历史行为' });
      positives.push({ what: '默认存放 = ' + assets, needle: '没有 `scriptStorage` → 默认 `' + assets + '`' });
      positives.push({ what: 'native + 等级 0 ⇒ 提到 1', needle: '但等级是 0 时会被自动提到 1' });
      positives.push({ what: '五档选择文案', needle: '五档选择（不加密 / 加密 / 快照 / 加密 so / 快照 so）' });

      // ---- 反向断言（限定在 §scope§ 内；历史语境行豁免）----
      const negatives = [];
      const scope = (ctx && ctx.scope) || { start: '## 1.1 脚本 APK 的保护等级', end: '## ' };
      const markers = (ctx && ctx.markers) || ['历史', '曾经', '以前', '已修', '更正中', 'bug'];
      negatives.push({
        what: '表格的 encryptLevel 列不得出现 > MAX_LEVEL(=' + maxLevel + ') 的值',
        test(docText) {
          for (const ln of scopedLines(docText, scope)) {
            if (!ln.text.startsWith('|')) continue;
            const cells = ln.text.split('|').map((c) => c.trim());
            if (cells.length < 4) continue;
            const levelCell = cells[2];
            if (!/^\d+$/.test(levelCell)) continue;
            if (Number(levelCell) > maxLevel) return { line: ln.line, text: ln.text, detail: 'encryptLevel=' + levelCell };
          }
          return null;
        },
      });
      negatives.push({
        what: '取值规则里不得把「默认 0（不加密）」写成现行规则',
        test(docText) {
          for (const ln of scopedLines(docText, scope)) {
            if (isHistoryLine(ln.text, markers)) continue;
            if (!/^\d+\. /.test(ln.text)) continue;
            if (/默认 ?0/.test(ln.text)) return { line: ln.line, text: ln.text, detail: '默认 0' };
          }
          return null;
        },
      });
      negatives.push({
        what: '取值规则里不得把历史 bug（等级 2 + native 被推成「加密 so」）当作现行规则',
        test(docText) {
          for (const ln of scopedLines(docText, scope)) {
            if (isHistoryLine(ln.text, markers)) continue;
            if (!/^\d+\. /.test(ln.text)) continue;
            if (/被推成|降级为/.test(ln.text)) return { line: ln.line, text: ln.text, detail: '把降级当成现行规则' };
          }
          return null;
        },
      });
      return { positives, negatives, meta: { none, enc, comp, maxLevel, def, assets, native } };
    },
  },
];

export function findContract(id) {
  return CONTRACTS.find((c) => c.id === id) || null;
}

/** 纯函数：给定代码文本与文档文本，返回全部失败项（正/反向统一口径）。 */
export function evaluate(contract, codeText, docText) {
  const spec = contract.build(codeText, { scope: contract.scope, markers: contract.historyMarkers });
  if (!scopeResolved(docText, contract.scope)) {
    return { total: 1, positives: 0, negatives: 0, failures: [{ kind: 'scope', what: '契约作用域未命中：scope.start 在文档里找不到（文档结构变了？）', expected: contract.scope.start }] };
  }
  const failures = [];
  let total = 0;
  for (const p of spec.positives) {
    total++;
    if (!docText.includes(p.needle)) failures.push({ kind: 'missing', what: p.what, expected: p.needle });
  }
  for (const n of spec.negatives) {
    total++;
    const hit = n.test(docText);
    if (hit) failures.push({ kind: 'forbidden', what: n.what, line: hit.line, text: hit.text, detail: hit.detail });
  }
  return { total, positives: spec.positives.length, negatives: spec.negatives.length, failures, meta: spec.meta };
}

/** 统一的失败行格式化（CLI 与单测共用 ⇒ 单测可断言「what 出现在输出里」）。 */
export function formatFailure(f) {
  if (f.kind === 'forbidden') {
    return '   - [FORBIDDEN] ' + f.what + '  ==> doc:' + f.line + ' ' + String(f.text).slice(0, 80) + ' (' + f.detail + ')';
  }
  return '   - [MISSING] ' + f.what + '  ==> 期望文档包含: ' + f.expected;
}

export function readText(rel, root) {
  const p = path.isAbsolute(rel) ? rel : path.join(root, rel);
  if (!fs.existsSync(p)) throw new Error('找不到文件：' + p);
  return fs.readFileSync(p, 'utf8');
}
