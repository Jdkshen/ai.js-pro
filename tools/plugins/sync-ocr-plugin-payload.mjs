#!/usr/bin/env node
// task-131：OCR 插件载荷的**可复现**同步（libs 从仓库/AAR 取，模型从现有资产映射）+ 锁文件。
//
// 用法：
//   node tools/plugins/sync-ocr-plugin-payload.mjs           # 同步 + 写锁文件 + 写来源说明
//   node tools/plugins/sync-ocr-plugin-payload.mjs --check    # 只核对（不写），不一致 exit 1
//
// 来源策略（全可复现，逐项打 source-chain 日志）：
//   libppocr.so            <- modules/engine/src/main/jniLibs/arm64-v8a/libppocr.so（仓库内）
//   libc++_shared.so       <- modules/engine/libs/opencv-5.0.0-16kb-page-fix.aar -> jni/arm64-v8a/libc++_shared.so（仓库内）
//   libmlkit_google_ocr_.. <- Gradle 缓存 AAR：com.google.mlkit:text-recognition-bundled-common:17.0.0 -> jni/arm64-v8a/libmlkit_google_ocr_pipeline.so
//   assets/ocr/models/**   <- mlkit-google-ocr-models/**（仓库资产或构建产物，逐文件记来源）

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";
import crypto from "node:crypto";
import process from "node:process";

const ROOT = process.cwd();
const ABI = "arm64-v8a";
const OUT = path.join(ROOT, "plugins/ocr-plugin/src/main");
const LOCK = path.join(ROOT, "plugins/ocr-plugin/payload-sources.json");
const DOC = path.join(ROOT, "tools/plugins/OCR-PAYLOAD-SOURCES.md");
const checkOnly = process.argv.includes("--check");
const warnings = [];
const entries = [];
let copied = 0, kept = 0, failed = 0, bytes = 0;

const sha256 = (buf) => crypto.createHash("sha256").update(buf).digest("hex");
const elfMachine = (buf) => {
  if (buf.length < 20 || buf[0] !== 0x7f || buf[1] !== 0x45) return "not-elf";
  const m = buf.readUInt16LE(18);
  return "0x" + m.toString(16) + (m === 0xb7 ? " (AArch64)" : " (NOT aarch64)");
};

function readZipEntry(zipPath, entryName) {
  const buf = fs.readFileSync(zipPath);
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 70000; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error("不是有效 zip（找不到 EOCD）：" + zipPath);
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error("中央目录损坏");
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString("utf8", off + 46, off + 46 + nameLen);
    if (name === entryName) {
      const lnameLen = buf.readUInt16LE(localOff + 26);
      const lextraLen = buf.readUInt16LE(localOff + 28);
      const start = localOff + 30 + lnameLen + lextraLen;
      const data = buf.subarray(start, start + compSize);
      return method === 0 ? Buffer.from(data) : zlib.inflateRawSync(data);
    }
    off += 46 + nameLen + extraLen + commentLen;
  }
  throw new Error("AAR 内找不到 " + entryName);
}

function writeTarget(relTarget, buf, info) {
  const target = path.join(OUT, relTarget);
  const digest = sha256(buf);
  const record = Object.assign({ target: relTarget, sha256: digest, bytes: buf.length }, info);
  if (relTarget.endsWith(".so")) record.abi = elfMachine(buf);
  entries.push(record);
  if (fs.existsSync(target)) {
    const same = sha256(fs.readFileSync(target)) === digest && fs.statSync(target).size === buf.length;
    if (same) { kept += 1; console.log("[ocr-payload] KEEP   " + relTarget + " sha256=" + digest.slice(0, 16) + " bytes=" + buf.length + (record.abi ? " abi=" + record.abi : "")); return; }
    if (checkOnly) { failed += 1; console.error("[ocr-payload] MISMATCH " + relTarget); return; }
  } else if (checkOnly) {
    failed += 1; console.error("[ocr-payload] MISSING  " + relTarget); return;
  }
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, buf);
  copied += 1; bytes += buf.length;
  console.log("[ocr-payload] COPY   " + relTarget + " sha256=" + digest.slice(0, 16) + " bytes=" + buf.length + (record.abi ? " abi=" + record.abi : ""));
}

const ppocrPath = path.join(ROOT, "modules/engine/src/main/jniLibs/" + ABI + "/libppocr.so");
if (fs.existsSync(ppocrPath)) {
  console.log("[ocr-payload] source-chain libppocr.so -> modules/engine/src/main/jniLibs/" + ABI + "/libppocr.so");
  writeTarget("jniLibs/" + ABI + "/libppocr.so", fs.readFileSync(ppocrPath), { source: "modules/engine/src/main/jniLibs/" + ABI + "/libppocr.so", sourceKind: "repo", regenerate: "仓库内文件（无需重新生成）" });
} else { failed += 1; console.error("[ocr-payload] 来源缺失：modules/engine/src/main/jniLibs/" + ABI + "/libppocr.so"); }

const opencvAar = path.join(ROOT, "modules/engine/libs/opencv-5.0.0-16kb-page-fix.aar");
if (fs.existsSync(opencvAar)) {
  try {
    const buf = readZipEntry(opencvAar, "jni/" + ABI + "/libc++_shared.so");
    console.log("[ocr-payload] source-chain libc++_shared.so -> opencv AAR jni/" + ABI + "/libc++_shared.so（仓库内）");
    writeTarget("jniLibs/" + ABI + "/libc++_shared.so", buf, {
      source: "modules/engine/libs/opencv-5.0.0-16kb-page-fix.aar!/jni/" + ABI + "/libc++_shared.so",
      sourceKind: "repo-aar",
      regenerate: "无需重新生成：AAR 已签入仓库；与宿主/node24 副本同哈希 ⇒ 同源去重成立"
    });
  } catch (error) { failed += 1; console.error("[ocr-payload] 从 OpenCV AAR 取 libc++_shared.so 失败：" + error.message); }
} else { failed += 1; console.error("[ocr-payload] 来源缺失：" + opencvAar); }

// mlkit 库：**本轮不带**（Lead 裁决）—— 默认后端是 ppocr，而 mlkit 分支目前仍随宿主
// （W1：Ocr 只读宿主 assets，没有外部目录入口）⇒ 插件里这份 ≈11MB 短期没有消费者。
// 重开条件：将来做 mlkit 外置（给 Ocr 加 models 目录入口 + 宿主不再随包 mlkit）时，把
// libmlkit_google_ocr_pipeline.so 加回 libs 清单（并把它加回 desiredLibs）。
console.log("[ocr-payload] skip libmlkit_google_ocr_pipeline.so（默认后端 ppocr；mlkit 仍随宿主，见文件头重开条件）");

// ---- ppocr 模型（默认后端只需要这 5 个；文件名由原生侧 PPOcr.MODEL_FILES 写死，不能改）----
const PPOCR_SOURCE = "apps/app/src/main/assets/models/ppocrv6";
const PPOCR_MODEL_FILES = [
  "PP_OCRv6_tiny_det.ncnn.param",
  "PP_OCRv6_tiny_det.ncnn.bin",
  "PP_OCRv6_tiny_rec.ncnn.param",
  "PP_OCRv6_tiny_rec.ncnn.bin",
  "ppocr_keys_v6_tiny.txt"
];
const desiredModels = new Set();
const ppocrDir = path.join(ROOT, PPOCR_SOURCE);
if (fs.existsSync(ppocrDir)) {
  console.log("[ocr-payload] source-chain assets/ocr/models -> " + PPOCR_SOURCE + "（ppocr 5 文件，顶层）");
  for (const name of PPOCR_MODEL_FILES) {
    const file = path.join(ppocrDir, name);
    if (!fs.existsSync(file)) {
      failed += 1;
      console.error("[ocr-payload] 缺少 ppocr 模型文件：" + PPOCR_SOURCE + "/" + name + "（PPOcr.MODEL_FILES 写死的名字）");
      continue;
    }
    desiredModels.add("assets/ocr/models/" + name);
    writeTarget("assets/ocr/models/" + name, fs.readFileSync(file), {
      source: PPOCR_SOURCE + "/" + name,
      sourceKind: "repo-asset",
      regenerate: "仓库内文件（无需重新生成）"
    });
  }
} else {
  failed += 1;
  console.error("[ocr-payload] 来源缺失：ppocr 模型目录 " + PPOCR_SOURCE);
}

// ---- 清理：payload 目录里不再属于本次来源集合的文件（避免旧 mlkit 树等残留）----
const desiredLibs = new Set(["jniLibs/" + ABI + "/libppocr.so", "jniLibs/" + ABI + "/libc++_shared.so"]);
function pruneUnlisted(dirRelative, keep) {
  const dir = path.join(OUT, dirRelative);
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const rel = dirRelative + "/" + entry.name;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) { pruneUnlisted(rel, keep); continue; }
    if (keep.has(rel)) continue;
    if (checkOnly) { failed += 1; console.error("[ocr-payload] EXTRA  " + rel + "（应被清理；跑一次不带 --check 的同步）"); }
    else { fs.unlinkSync(full); console.log("[ocr-payload] PRUNE  " + rel); }
  }
}
pruneUnlisted("assets/ocr/models", desiredModels);
pruneUnlisted("jniLibs/" + ABI, desiredLibs);

// 清掉 prune 之后留下的空目录（否则 payload 里会残留 aksara/gocr/taser 这类空壳）
function pruneEmptyDirs(dirRelative) {
  const dir = path.join(OUT, dirRelative);
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const rel = dirRelative + "/" + entry.name;
    pruneEmptyDirs(rel);
    const child = path.join(OUT, rel);
    if (fs.readdirSync(child).length === 0) {
      if (checkOnly) { failed += 1; console.error("[ocr-payload] EMPTY-DIR " + rel); }
      else { fs.rmdirSync(child); console.log("[ocr-payload] RMDIR  " + rel); }
    }
  }
}
pruneEmptyDirs("assets/ocr/models");


// ---- DT_NEEDED 审计（readelf -d 等价；只告警不失败）----
// 结论（dev 取证）：libppocr.so 依赖 7 个 libopencv_* + libjnigraphics（系统库）⇒ 这些由**宿主**提供
// （宿主本来就随包 OpenCV；插件不带也不该带，否则插件再涨 ≈26MB）。真机上若 System.load 解析不到，
// 说明宿主 soname 与插件 NEEDED 不一致 —— 那要按 W1 的适配层方案处理（见报告）。
function elfNeeded(buf) {
  if (buf.length < 64 || buf.readUInt32LE(0) !== 0x464c457f || buf[4] !== 2) return [];
  const phoff = Number(buf.readBigUInt64LE(32));
  const phentsize = buf.readUInt16LE(54), phnum = buf.readUInt16LE(56);
  let dynOff = -1, dynSize = 0; const loads = [];
  for (let i = 0; i < phnum; i++) {
    const off = phoff + i * phentsize, type = buf.readUInt32LE(off);
    const offset = Number(buf.readBigUInt64LE(off + 8));
    const vaddr = Number(buf.readBigUInt64LE(off + 16));
    const filesz = Number(buf.readBigUInt64LE(off + 32));
    if (type === 1) loads.push({ vaddr, offset, filesz });
    if (type === 2) { dynOff = offset; dynSize = filesz; }
  }
  const v2o = (v) => { for (const l of loads) { if (v >= l.vaddr && v < l.vaddr + l.filesz) return l.offset + (v - l.vaddr); } return -1; };
  let strtab = 0; const need = [];
  for (let p = dynOff; p < dynOff + dynSize; p += 16) {
    const tag = Number(buf.readBigUInt64LE(p)), val = Number(buf.readBigUInt64LE(p + 8));
    if (tag === 0) break;
    if (tag === 1) need.push(val);
    if (tag === 5) strtab = val;
  }
  const base = v2o(strtab);
  return need.map((v) => { let o = base + v, e = o; while (buf[e] !== 0) e++; return buf.toString("utf8", o, e); });
}

const SYSTEM_LIB = /^lib(c|m|dl|log|z|android|jnigraphics|stdc\+\+|GLESv[0-9])\.so$/;
const shipped = new Set(entries.filter((x) => x.target.startsWith("jniLibs/")).map((x) => x.target.split("/").pop()));
const neededReport = {};
for (const name of shipped) {
  const file = path.join(OUT, "jniLibs", ABI, name);
  if (!fs.existsSync(file)) continue;
  const needed = elfNeeded(fs.readFileSync(file));
  neededReport[name] = needed;
  const hostProvided = needed.filter((n) => !SYSTEM_LIB.test(n) && !shipped.has(n));
  console.log("[ocr-payload] NEEDED " + name + " = " + JSON.stringify(needed));
  if (hostProvided.length > 0) {
    warnings.push(name + " 的 NEEDED 里有插件未带的库（应由宿主提供）：" + hostProvided.join(", "));
    console.log("[ocr-payload] WARN " + name + " 依赖宿主提供：" + hostProvided.join(", "));
  }
}

if (!checkOnly && failed === 0) {
  fs.writeFileSync(LOCK, JSON.stringify({ schema: 1, note: "由 tools/plugins/sync-ocr-plugin-payload.mjs 生成；每项含来源/sha256/字节/ABI 与如何重新生成。", abi: ABI, mlkitLibExcluded: "默认后端 ppocr；mlkit 仍随宿主 ⇒ 插件不带 libmlkit_google_ocr_pipeline.so（重开条件见脚本头）", needed: neededReport, entries }, null, 2) + "\n", "utf8");
  const lines = ["# OCR 插件载荷来源（task-131）", "", "由 tools/plugins/sync-ocr-plugin-payload.mjs 生成；复核命令：node tools/plugins/sync-ocr-plugin-payload.mjs --check", "", "| 目标 | 来源 | 字节 | sha256(前16) | ABI |", "| --- | --- | --- | --- | --- |"];
  for (const e of entries) lines.push("| " + e.target + " | " + e.source + " | " + e.bytes + " | " + e.sha256.slice(0, 16) + " | " + (e.abi || "-") + " |");
  if (warnings.length) { lines.push("", "## 警告", ""); for (const w of warnings) lines.push("- " + w); }
  fs.writeFileSync(DOC, lines.join("\n") + "\n", "utf8");
  console.log("[ocr-payload] lock=" + path.relative(ROOT, LOCK) + " entries=" + entries.length + " ; doc=" + path.relative(ROOT, DOC));
}
console.log("[ocr-payload] done mode=" + (checkOnly ? "check" : "sync") + " copied=" + copied + " kept=" + kept + " failed=" + failed + " bytes=" + bytes);
process.exit(failed === 0 ? 0 : 1);