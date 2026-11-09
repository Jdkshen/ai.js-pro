#!/usr/bin/env node
// Flutter <-> native local-bridge contract checker (F1 / task-47).
// Usage: node tools/flutter-bridge-contract.mjs   (run from repo root)
// Exit 0 = PASS (0 gaps), exit 1 = FAIL.
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..');
const P = (...p) => join(repo, ...p);

const NATIVE_DIR = P('apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc');
const FAMILY = ['PocBridge.java','PocIntegration.java','PocWorkspace.java','PocPackaging.java','PocRuntime.java','PocServices.java','PocAppInfo.java'];
const DOC = P('docs/guides/Flutter本地桥API.md');
const DART_LIB = P('flutter-ui/lib');

// MCP tool names passed through toolkitCall (not bridge methods).
const TOOLKIT_TOOLS = ['toolkit_status','apk_list','apk_list_available_apks','apk_workspace_open','apk_workspace_delete','apk_read_text','apk_session_create','apk_continue','file_list','zip_list'];

const read = (f) => readFileSync(f, 'utf8');
const casesOf = (t) => { const out = []; const re = /case\s+"([^"]+)"\s*:/g; let m; while ((m = re.exec(t))) out.push(m[1]); return out; };
const uniq = (a) => Array.from(new Set(a));

// ---- native ----
if (!existsSync(NATIVE_DIR)) { console.log('FAIL: native dir not found: ' + NATIVE_DIR); process.exit(1); }
const nativeByFile = {};
for (const f of FAMILY) {
  const p = join(NATIVE_DIR, f);
  nativeByFile[f] = existsSync(p) ? casesOf(read(p)) : [];
}
const nativeBridge = nativeByFile['PocBridge.java'];
const nativeAll = uniq(FAMILY.flatMap((f) => nativeByFile[f]));
const internalOnly = nativeAll.filter((n) => !nativeBridge.includes(n));
// 异步族方法不走 PocBridge 的 switch，而是由各族的 isAsync(method) 提前分流。
const asyncRouted = [];
for (const ff of FAMILY) {
  const t = nativeByFile[ff].length ? read(join(NATIVE_DIR, ff)) : '';
  const reIs = /(?:static\s+)?boolean\s+isAsync\s*\([^)]*\)\s*\{([\s\S]*?)\n\s*\}/g;
  let mm;
  while ((mm = reIs.exec(t))) {
    const reQ = /"([A-Za-z0-9_]+)"/g;
    let q;
    while ((q = reQ.exec(mm[1]))) if (!asyncRouted.includes(q[1])) asyncRouted.push(q[1]);
  }
}
const reachable = uniq(nativeBridge.concat(asyncRouted));
const unreachableLabels = nativeAll.filter((n) => !reachable.includes(n));

// ---- dart ----
const dartFiles = [];
(function walk(dir) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p);
    else if (e.endsWith('.dart')) dartFiles.push(p);
  }
})(DART_LIB);
const dartCalls = [];
const dartChannels = [];
for (const f of dartFiles) {
  const t = read(f);
  let m;
  const reCall = /(?:\bcall|invokeMethod(?:<[^>]*>)?)\s*\(\s*'([A-Za-z0-9_]+)'/g;
  while ((m = reCall.exec(t))) dartCalls.push({ name: m[1], file: f });
  const reChan = /(BasicMessageChannel(?:<[^>]*>)?|EventChannel(?:<[^>]*>)?|MethodChannel)\s*\(\s*'([^']+)'/g;
  while ((m = reChan.exec(t))) dartChannels.push({ kind: m[1].replace(/<[^>]*>/, ''), name: m[2] });
}
const dartNames = uniq(dartCalls.map((c) => c.name));
const dartBridge = dartNames.filter((n) => !TOOLKIT_TOOLS.includes(n));
const dartBack = dartNames.filter((n) => n === 'finish');
const toolkitsUsed = TOOLKIT_TOOLS.filter((n) => dartNames.includes(n));
const channelsDart = uniq(dartChannels.map((c) => c.name)).sort();

// ---- doc ----
if (!existsSync(DOC)) { console.log('FAIL: doc not found: ' + DOC); process.exit(1); }
const doc = read(DOC);
const docMethods = [];
const secStart = doc.indexOf('## 三、方法总表');
const secEnd = doc.indexOf('## 四、');
const sec = secStart >= 0 && secEnd > secStart ? doc.slice(secStart, secEnd) : '';
const reRow = /^\|\s*`([A-Za-z0-9_]+)`\s*\|/gm;
let dm;
while ((dm = reRow.exec(sec))) docMethods.push(dm[1]);
const docChannels = ['autojs.local.bridge','autojs.local.logs','com.jdkshen.aijspro/back'].filter((c) => doc.includes(c));
const docEvents = ['nativePackagingReady','pendingAction','taskChanged','accessibilityState','debugConnectionState','prefsChanged','processResumed'].filter((e) => doc.includes(e));

// ---- compare ----
const gaps = [];
const missingInDoc = nativeBridge.filter((n) => !docMethods.includes(n));
const extraInDoc = docMethods.filter((n) => !nativeBridge.includes(n));
const dartNotNative = dartBridge.filter((n) => !reachable.includes(n) && !dartBack.includes(n));
const chanMissing = ['autojs.local.bridge','autojs.local.logs','com.jdkshen.aijspro/back'].filter((c) => !channelsDart.includes(c));
const EV_FILE = P('apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/FlutterBridgeEvents.java');
const EV_CODEC = P('apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/FlutterBridgeEventCodec.java');
const evCode = existsSync(EV_CODEC) ? uniq(Array.from(read(EV_CODEC).matchAll(/static\s+final\s+String\s+EVENT_[A-Z_]+\s*=\s*"([^"]+)"/g)).map((m) => m[1])) : [];
const evNew = ['taskChanged','accessibilityState','debugConnectionState','prefsChanged','processResumed'];
const evMissing = evNew.filter((e) => !docEvents.includes(e));
const evCodeMissing = evCode.filter((e) => !docEvents.includes(e));
if (missingInDoc.length) gaps.push('native-only(文档缺): ' + missingInDoc.join(','));
if (extraInDoc.length) gaps.push('doc-only(原生无): ' + extraInDoc.join(','));
if (chanMissing.length) gaps.push('Dart 缺通道: ' + chanMissing.join(','));
if (evMissing.length) gaps.push('文档缺本批新增事件: ' + evMissing.join(','));
if (evCodeMissing.length) gaps.push('FlutterBridgeEvents.java 有、文档未列的事件: ' + evCodeMissing.join(','));
if (TOOLKIT_TOOLS.some((t) => !doc.includes(t))) gaps.push('文档未列全 toolkitCall 工具名');

const strict = process.argv.includes('--strict');
if (strict && dartNotNative.length) gaps.push('dart 死调用(原生无该方法): ' + dartNotNative.join(','));
const out = [
  'native=' + nativeBridge.length,
  'dart=' + dartBridge.length,
  'toolkits=' + toolkitsUsed.length,
  'doc=' + docMethods.length,
  '缺口=[' + gaps.join(' | ') + ']',
].join(' ');
console.log(out);
console.log('detail: nativeAll=' + nativeAll.length + ' reachable=' + reachable.length + ' asyncRouted=[' + asyncRouted.join(',') + ']');
console.log('detail: 非桥方法(内部动作标签)=' + unreachableLabels.length + ' [' + unreachableLabels.join(',') + ']');
console.log('WARN dartDeadCalls=' + dartNotNative.length + ' [' + dartNotNative.map((n) => n + '@' + ((dartCalls.find((c) => c.name === n) || {}).file || '?')).join(', ') + ']' + (dartNotNative.length ? '  <-- Dart 调了但原生没有 case；默认不 gate，--strict 时 FAIL' : ''));
console.log('detail: dartNames=' + dartNames.length + ' backChannelOnly=[' + dartBack.join(',') + '] channels=' + channelsDart.join(','));
console.log('detail: docEvents=' + docEvents.length + '/7 evCode=[' + evCode.join(',') + '] docChannels=' + docChannels.length + '/3');
if (gaps.length === 0) { console.log('PASS: 0 gaps'); process.exit(0); }
console.log('FAIL: ' + gaps.length + ' gap group(s)');
process.exit(1);
