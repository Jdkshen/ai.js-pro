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

const mlkitCoord = "com.google.mlkit:text-recognition-bundled-common:17.0.0";
const mlkitAar = path.join(os.homedir(), ".gradle/caches/modules-2/files-2.1/com.google.mlkit/text-recognition-bundled-common/17.0.0/ad2396ea7b02feb026c821cb6aded1216509b0fd/text-recognition-bundled-common-17.0.0.aar");
const strippedMlkit = path.join(ROOT, "apps/app/build/intermediates/stripped_native_libs/miuixCompatRelease/stripMiuixCompatReleaseDebugSymbols/out/lib/" + ABI + "/libmlkit_google_ocr_pipeline.so");
if (fs.existsSync(mlkitAar)) {
  try {
    const buf = readZipEntry(mlkitAar, "jni/" + ABI + "/libmlkit_google_ocr_pipeline.so");
    console.log("[ocr-payload] source-chain libmlkit_google_ocr_pipeline.so -> " + mlkitCoord + " AAR（Gradle 缓存）");
    if (fs.existsSync(strippedMlkit)) {
      const same = sha256(fs.readFileSync(strippedMlkit)) === sha256(buf);
      console.log("[ocr-payload] AAR 未 strip 版 vs app stripped 版 same=" + same + "（不同则采用 AAR 版：它才是发布源）");
      if (!same) warnings.push("mlkit：AAR 未 strip 版与 app stripped 版哈希不同，已采用 AAR 版");
    }
    writeTarget("jniLibs/" + ABI + "/libmlkit_google_ocr_pipeline.so", buf, {
      source: mlkitCoord + " -> AAR 内 jni/" + ABI + "/libmlkit_google_ocr_pipeline.so",
      sourceKind: "gradle-cache-aar",
      aarCachePath: "~/.gradle/caches/modules-2/files-2.1/com.google.mlkit/text-recognition-bundled-common/17.0.0/ad2396ea7b02feb026c821cb6aded1216509b0fd/text-recognition-bundled-common-17.0.0.aar",
      regenerate: "解压缓存 AAR 的 jni/" + ABI + "/libmlkit_google_ocr_pipeline.so（AAR 由 Gradle 依赖 " + mlkitCoord + " 拉取；仓库内无副本）"
    });
  } catch (error) { failed += 1; console.error("[ocr-payload] 从 mlkit AAR 取 lib 失败：" + error.message); }
} else { failed += 1; console.error("[ocr-payload] 来源缺失（Gradle 缓存 AAR）：" + mlkitAar); }

const modelCandidates = [
  "modules/engine/src/main/assets/mlkit-google-ocr-models",
  "apps/inrt/src/main/assets/mlkit-google-ocr-models",
  "apps/app/src/main/assets/mlkit-google-ocr-models",
  "apps/inrt/build/intermediates/assets/release/mergeReleaseAssets/mlkit-google-ocr-models",
  "apps/app/build/intermediates/assets/miuixCompatRelease/mergeMiuixCompatReleaseAssets/mlkit-google-ocr-models"
];
let modelSource = null;
for (const candidate of modelCandidates) {
  const full = path.join(ROOT, candidate);
  if (fs.existsSync(full)) { modelSource = { full, relative: candidate }; break; }
}
if (modelSource) {
  console.log("[ocr-payload] source-chain assets/ocr/models -> " + modelSource.relative);
  const walk = (dir, out) => { for (const e of fs.readdirSync(dir, { withFileTypes: true })) { const f = path.join(dir, e.name); if (e.isDirectory()) walk(f, out); else out.push(f); } return out; };
  for (const file of walk(modelSource.full, [])) {
    const relative = path.relative(modelSource.full, file).split(path.sep).join("/");
    writeTarget("assets/ocr/models/" + relative, fs.readFileSync(file), {
      source: modelSource.relative + "/" + relative,
      sourceKind: modelSource.relative.includes("build/intermediates") ? "build-output" : "repo-asset",
      regenerate: modelSource.relative + "（资产合并产物；重新生成 = 跑一次 :app 资产合并，或从 mlkit AAR 的 assets 取）"
    });
  }
} else { failed += 1; console.error("[ocr-payload] 来源缺失：OCR 模型目录（试过 " + modelCandidates.length + " 个路径）"); }

if (!checkOnly && failed === 0) {
  fs.writeFileSync(LOCK, JSON.stringify({ schema: 1, note: "由 tools/plugins/sync-ocr-plugin-payload.mjs 生成；每项含来源/sha256/字节/ABI 与如何重新生成。", abi: ABI, entries }, null, 2) + "\n", "utf8");
  const lines = ["# OCR 插件载荷来源（task-131）", "", "由 tools/plugins/sync-ocr-plugin-payload.mjs 生成；复核命令：node tools/plugins/sync-ocr-plugin-payload.mjs --check", "", "| 目标 | 来源 | 字节 | sha256(前16) | ABI |", "| --- | --- | --- | --- | --- |"];
  for (const e of entries) lines.push("| " + e.target + " | " + e.source + " | " + e.bytes + " | " + e.sha256.slice(0, 16) + " | " + (e.abi || "-") + " |");
  if (warnings.length) { lines.push("", "## 警告", ""); for (const w of warnings) lines.push("- " + w); }
  fs.writeFileSync(DOC, lines.join("\n") + "\n", "utf8");
  console.log("[ocr-payload] lock=" + path.relative(ROOT, LOCK) + " entries=" + entries.length + " ; doc=" + path.relative(ROOT, DOC));
}
console.log("[ocr-payload] done mode=" + (checkOnly ? "check" : "sync") + " copied=" + copied + " kept=" + kept + " failed=" + failed + " bytes=" + bytes);
process.exit(failed === 0 ? 0 : 1);
