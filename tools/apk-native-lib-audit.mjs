#!/usr/bin/env node
// task-67: 打包模板 / APK 原生库审计（与 NativeLibPolicy 同判据，零依赖）。
//
// 用法：
//   node tools/apk-native-lib-audit.mjs <apk> [--remove-opencv] [--plugins a,b] [--puzzle]
//        [--expect-abi arm64-v8a,armeabi-v7a] [--fail-on-leak] [--json]
//   node tools/apk-native-lib-audit.mjs --aar <aar-or-zip> [--needed <entry.so>]
//
// 判据来源：apps/app/src/main/java/com/jdkshen/aijspro/autojs/build/NativeLibPolicy.kt:13-64
//（TEMPLATE_REQUIRED / PUZZLE_ENGINE+PUZZLE_HINTS / OPENCV_+libtbb / OCR_LIBS / libv8|flutter|node）= 同判据移植。
import { readFileSync, existsSync } from 'node:fs';
import { basename } from 'node:path';

// ---------------- ZIP（只读 central directory，够列 .so 名与原始大小） ----------------
function zipEntries(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 22 - 65558; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip (no EOCD)');
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const out = [];
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) break;
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const rawSize = buf.readUInt32LE(off + 24);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    out.push({ name, method, compSize, rawSize, localOff });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

// 解压单个 entry（只支持 stored/deflate，模板与 AAR 都是这两类）
import { inflateRawSync } from 'node:zlib';
function zipRead(buf, entry) {
  const lo = entry.localOff;
  if (buf.readUInt32LE(lo) !== 0x04034b50) throw new Error('bad local header for ' + entry.name);
  const nameLen = buf.readUInt16LE(lo + 26);
  const extraLen = buf.readUInt16LE(lo + 28);
  const dataOff = lo + 30 + nameLen + extraLen;
  const raw = buf.subarray(dataOff, dataOff + entry.compSize);
  return entry.method === 0 ? raw : inflateRawSync(raw);
}

// ---------------- ELF：读 DT_NEEDED（判断单库依赖哪些模块） ----------------
function elfNeeded(buf) {
  if (buf.length < 64 || buf[0] !== 0x7f || buf[1] !== 0x45) return null;
  const is64 = buf[4] === 2;
  const le = buf[5] === 1;
  const u16 = (o) => le ? buf.readUInt16LE(o) : buf.readUInt16BE(o);
  const u32 = (o) => le ? buf.readUInt32LE(o) : buf.readUInt32BE(o);
  const u64 = (o) => Number(le ? buf.readBigUInt64LE(o) : buf.readBigUInt64BE(o));
  const phoff = is64 ? u64(32) : u32(28);
  const phentsize = is64 ? u16(54) : u16(42);
  const phnum = is64 ? u16(56) : u16(44);
  let dyn = null;
  for (let i = 0; i < phnum; i++) {
    const p = phoff + i * phentsize;
    if (u32(p) === 2) { dyn = { off: is64 ? u64(p + 8) : u32(p + 4), size: is64 ? u64(p + 32) : u32(p + 16) }; break; }
  }
  if (!dyn) return null;
  const step = is64 ? 16 : 8;
  let strtab = 0, strsz = 0;
  const tags = [];
  for (let o = dyn.off; o < dyn.off + dyn.size; o += step) {
    const tag = is64 ? Number(buf.readBigInt64LE(o)) : u32(o);
    const val = is64 ? u64(o + 8) : u32(o + 4);
    if (tag === 0) break;
    if (tag === 5) strtab = val;
    else if (tag === 10) strsz = val;
    else if (tag === 1) tags.push(val);
  }
  const out = [];
  for (const v of tags) {
    if (strtab + v >= buf.length) continue;
    let end = buf.indexOf(0, strtab + v);
    if (end < 0) end = buf.length;
    out.push(buf.toString('utf8', strtab + v, end));
  }
  return out;
}

// ---------------- NativeLibPolicy 同判据（移植 NativeLibPolicy.kt:13-64） ----------------
const TEMPLATE_REQUIRED = new Set(['libaijscrypto.so','libc++_shared.so','libquickjs.so','libquickjs_jni.so']);
const OCR_LIBS = new Set(['libppocr.so','libmlkit_google_ocr_pipeline.so']);
const OPENCV_PREFIX = 'libopencv_';
const TBB_PREFIX = 'libtbb';
const PUZZLE_ENGINE = 'libpikafish.so';
const PUZZLE_HINTS = ['pikafish','xiangqi','象棋'];

function classify(entryName, needs) {
  const name = entryName.split('/').pop().toLowerCase();
  if (TEMPLATE_REQUIRED.has(name)) return { decision: 'KEEP', source: 'template', reason: 'inrt 运行时必需（模板瘦身补齐）' };
  if (name === PUZZLE_ENGINE) {
    const wants = needs.puzzle || needs.plugins.some((p) => PUZZLE_HINTS.some((h) => p.toLowerCase().includes(h)));
    return wants
      ? { decision: 'KEEP', source: 'project', reason: '工程声明/使用了象棋引擎（xiangqi 插件或源码命中 pikafish/xiangqi）' }
      : { decision: 'DROP', source: 'leak', reason: '象棋引擎与本工程无关：未声明 xiangqi/pikafish 插件、源码也未命中 → 不该带' };
  }
  if (name.startsWith(OPENCV_PREFIX) || name.startsWith(TBB_PREFIX)) {
    return needs.removeOpenCv
      ? { decision: 'DROP', source: 'project', reason: 'project.json optimization.removeOpenCv=true → 不带 OpenCV/TBB' }
      : { decision: 'KEEP', source: 'project', reason: '工程未声明 removeOpenCv → 保留 OpenCV/TBB（宿主形态是拆库）' };
  }
  if (OCR_LIBS.has(name)) return { decision: 'KEEP', source: 'template', reason: '内建 OCR（ppocr/mlkit pipeline）覆盖 Pro 的 mlkit 插件能力' };
  if (name.startsWith('libv8') || name.includes('flutter') || name.includes('node')) {
    return { decision: 'DROP', source: 'leak', reason: '宿主里其它运行时（v8/flutter/node）不属于本工程产物' };
  }
  return { decision: 'KEEP', source: 'unknown', reason: '未识别：保守保留（若确认无关请加进模板必需或工程策略）' };
}

// ---------------- CLI ----------------
const argv = process.argv.slice(2);
const flag = (n) => argv.includes(n);
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const positional = argv.filter((a, i) => !a.startsWith('--') && (i === 0 || !argv[i - 1].startsWith('--')));
const target = opt('--aar', positional[0]);
if (!target) { console.log('usage: node tools/apk-native-lib-audit.mjs <apk> [--remove-opencv] [--plugins a,b] [--puzzle] [--expect-abi ...] [--fail-on-leak] [--json]'); console.log('       node tools/apk-native-lib-audit.mjs --aar <aar> [--needed <entry.so>]'); process.exit(2); }
if (!existsSync(target)) { console.error('file not found: ' + target); process.exit(2); }
const buf = readFileSync(target);
const entries = zipEntries(buf);
const libs = entries.filter((e) => /^(?:lib|jni)\/[^/]+\/.+\.so$/.test(e.name));
const abis = Array.from(new Set(libs.map((e) => e.name.split('/')[1]))).sort();
const byAbi = {};
for (const a of abis) byAbi[a] = libs.filter((e) => e.name.split('/')[1] === a);

if (flag('--aar')) {
  console.log('AAR ' + basename(target) + '  ' + buf.length + ' B');
  for (const a of abis) {
    const tot = byAbi[a].reduce((s, e) => s + e.rawSize, 0);
    console.log('  ' + a + ': ' + byAbi[a].length + ' libs, ' + (tot / 1048576).toFixed(2) + ' MiB');
    for (const e of byAbi[a].sort((x, y) => x.name.localeCompare(y.name))) console.log('     ' + e.name.split('/').pop().padEnd(34) + String(e.rawSize).padStart(10) + '  ' + (e.rawSize / 1048576).toFixed(2) + ' MiB');
  }
  const needEntry = opt('--needed');
  if (needEntry) {
    const abiFilter = opt('--needed-abi');
    const e = libs.find((x) => x.name.endsWith('/' + needEntry) && (!abiFilter || x.name.split('/')[1] === abiFilter));
    if (!e) { console.error('entry not found: ' + needEntry); process.exit(2); }
    const needed = elfNeeded(zipRead(buf, e));
    console.log('DT_NEEDED of ' + e.name + ': ' + (needed ? needed.join(', ') : '(not ELF / no PT_DYNAMIC)'));
  }
  const single = abis.map((a) => ({ a, n: byAbi[a].length, mono: byAbi[a].filter((e) => /libopencv_java\d\.so$/.test(e.name)).length }));
  console.log('single-opencv form: ' + single.map((s) => s.a + '=' + s.n + ' libs' + (s.mono ? ' (monolithic libopencv_javaN.so present)' : '')).join(' | '));
  process.exit(0);
}

// ---------------- 体积占比（--by-module）：把 .so 归到「模块」并按体积排序 ----------------
function moduleOf(name) {
  const oc = /^libopencv_(.+)\.so$/.exec(name);
  if (oc) return oc[1].startsWith('java') ? 'opencv-jni' : 'opencv-' + oc[1];
  if (name === 'libtbb.so') return 'tbb';
  if (name === 'libppocr.so' || name === 'libmlkit_google_ocr_pipeline.so') return 'ocr';
  if (['libaijscrypto.so','libc++_shared.so','libquickjs.so','libquickjs_jni.so'].includes(name)) return 'runtime-required';
  if (name === 'libpikafish.so') return 'puzzle';
  return 'other';
}
function groupByModule(rows, abiFilter) {
  const sel = abiFilter ? rows.filter((r) => r.abi === abiFilter) : rows;
  const map = new Map();
  for (const r of sel) {
    const m = moduleOf(r.name);
    const e = map.get(m) || { module: m, bytes: 0, libs: [] };
    e.bytes += r.size; e.libs.push(r.name); map.set(m, e);
  }
  const total = sel.reduce((s, r) => s + r.size, 0);
  return { total, items: Array.from(map.values()).sort((a, b) => b.bytes - a.bytes) };
}
const needs = { removeOpenCv: flag('--remove-opencv'), plugins: String(opt('--plugins', '')).split(',').filter(Boolean), puzzle: flag('--puzzle') };
const rows = libs.map((e) => ({ entry: e.name, abi: e.name.split('/')[1], size: e.rawSize, name: e.name.split('/').pop(), v: classify(e.name, needs) }));
const leaks = rows.filter((r) => r.v.source === 'leak' && r.v.decision === 'DROP');
const unknown = rows.filter((r) => r.v.source === 'unknown');
const expected = String(opt('--expect-abi', '')).split(',').filter(Boolean);
const missingAbi = expected.filter((a) => !abis.includes(a));

const byModule = flag('--by-module') ? abis.map((a) => { const g = groupByModule(rows, a); return { abi: a, total: g.total, items: g.items }; }) : null;
if (byModule && !flag('--json')) {
  for (const g of byModule) {
    console.log('== by-module [' + g.abi + ']  total ' + (g.total / 1048576).toFixed(2) + ' MiB / ' + (g.total / 1000000).toFixed(1) + ' MB');
    for (const it of g.items) {
      const pct = g.total ? (it.bytes * 100 / g.total) : 0;
      console.log('   ' + it.module.padEnd(20) + (it.bytes / 1048576).toFixed(2).padStart(8) + ' MiB  ' + (it.bytes / 1000000).toFixed(1).padStart(7) + ' MB  ' + pct.toFixed(1).padStart(5) + '%  [' + it.libs.join(',') + ']');
    }
  }
}
if (flag('--json')) {
  console.log(JSON.stringify({ apk: target, bytes: buf.length, abis, libs: rows, leaks, unknown, missingAbi, byModule }, null, 1));
} else {
  console.log('APK ' + basename(target) + '  ' + buf.length + ' B  libs=' + rows.length + '  abis=[' + abis.join(',') + ']');
  for (const r of rows) console.log('  ' + r.v.decision.padEnd(4) + ' ' + r.name.padEnd(34) + r.abi.padEnd(13) + String(r.size).padStart(10) + '  source=' + r.v.source.padEnd(8) + '  ' + r.v.reason);
  for (const a of abis) {
    const set = rows.filter((r) => r.abi === a);
    console.log('  [' + a + '] ' + set.length + ' libs, ' + (set.reduce((s, r) => s + r.size, 0) / 1048576).toFixed(2) + ' MiB');
  }
  console.log('leaks=' + leaks.length + (leaks.length ? ' [' + leaks.map((r) => r.name).join(',') + ']' : '') + '  unknown=' + unknown.length + (unknown.length ? ' [' + unknown.map((r) => r.name).join(',') + ']' : '') + '  missingAbi=[' + missingAbi.join(',') + ']');
}
if (flag('--fail-on-leak') && leaks.length) { console.log('FAIL: ' + leaks.length + ' leak(s)'); process.exit(1); }
if (missingAbi.length) { console.log('NOTE: expected ABI not present: ' + missingAbi.join(',')); }
process.exit(0);
