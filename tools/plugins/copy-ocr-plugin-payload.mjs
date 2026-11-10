#!/usr/bin/env node
// task-131：把 OCR 插件需要的 libs/模型从**现有产物**拷进 plugins/ocr-plugin（确定性 + 可核）。
//
// 用法：
//   node tools/plugins/copy-ocr-plugin-payload.mjs            # 拷贝（幂等：内容相同则跳过）
//   node tools/plugins/copy-ocr-plugin-payload.mjs --check    # 只校验既有拷贝与来源一致（CI 用）
//
// 设计要点（与 Lead 的口径一致）：
//  · **不入 git 大文件**：目标目录只由本脚本生成；
//  · **确定性**：固定来源清单（优先仓库源码路径，回退构建产物），逐个文件打印 来源路径 + sha256 + 字节数；
//  · **可核**：--check 会比对已拷贝文件的 sha256/大小，不一致即 exit 1；
//  · **契约路径映射**：模型从 assets/mlkit-google-ocr-models/** 映射到契约路径 assets/ocr/models/**。

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import process from "node:process";

const ROOT = process.cwd();
const ABI = "arm64-v8a";
const OUT = path.join(ROOT, "plugins/ocr-plugin/src/main");

// 每个条目：{ from: 候选来源（按序取第一个存在的）, to: 相对 OUT 的目标 }
const LIBS = ["libppocr.so", "libmlkit_google_ocr_pipeline.so", "libc++_shared.so"].map((name) => ({
  candidates: [
    "modules/engine/src/main/jniLibs/" + ABI + "/" + name,
    "modules/engine/build/intermediates/merged_jni_libs/release/mergeReleaseJniLibFolders/out/" + ABI + "/" + name,
    "apps/app/build/intermediates/stripped_native_libs/miuixCompatRelease/stripMiuixCompatReleaseDebugSymbols/out/lib/" + ABI + "/" + name
  ],
  to: "jniLibs/" + ABI + "/" + name,
  name
}));

// 回退链（每次用哪条都打日志）：仓库源码 → 构建产物。
// 说明：模型目前**只存在于构建产物/AAR 解包结果**（仓库 src/main 下没有同名目录），
// 所以锁文件里会记「如何重新生成」（见 tools/plugins/OCR-PAYLOAD-SOURCES.md 与 payload-sources.json）。
const MODELS = [{
  candidates: [
    "modules/engine/src/main/assets/mlkit-google-ocr-models",
    "apps/inrt/src/main/assets/mlkit-google-ocr-models",
    "apps/app/src/main/assets/mlkit-google-ocr-models",
    "apps/inrt/build/intermediates/assets/release/mergeReleaseAssets/mlkit-google-ocr-models",
    "apps/app/build/intermediates/assets/miuixCompatRelease/mergeMiuixCompatReleaseAssets/mlkit-google-ocr-models"
  ],
  to: "assets/ocr/models",
  name: "ocr-models",
  dir: true
}];

function firstExisting(candidates) {
  for (const candidate of candidates) {
    const full = path.join(ROOT, candidate);
    if (fs.existsSync(full)) return full;
  }
  return null;
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function walk(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

const checkOnly = process.argv.includes("--check");
let copied = 0, skipped = 0, failed = 0, bytes = 0;

// ELF 机器类型（0xB7 = AArch64）：用于 ③⑵「payload 只应含 arm64-v8a」的自证。
function elfMachine(file) {
  try {
    const b = fs.readFileSync(file);
    if (b.length < 20 || b[0] !== 0x7f || b[1] !== 0x45) return "(not ELF)";
    const machine = b.readUInt16LE(18);
    return "0x" + machine.toString(16) + (machine === 0xb7 ? " (AArch64 ✓)" : " (非 aarch64 ✗)");
  } catch (error) {
    return "(read failed)";
  }
}

function place(sourceFile, targetFile, label) {
  const sourceDigest = sha256(sourceFile);
  const sourceSize = fs.statSync(sourceFile).size;
  const exists = fs.existsSync(targetFile);
  if (exists) {
    const same = sha256(targetFile) === sourceDigest && fs.statSync(targetFile).size === sourceSize;
    if (same) {
      skipped += 1;
      console.log("[ocr-payload] KEEP   " + label + " sha256=" + sourceDigest.slice(0, 16) + " bytes=" + sourceSize);
      return;
    }
    if (checkOnly) {
      failed += 1;
      console.error("[ocr-payload] MISMATCH " + label + " 已拷贝文件与来源不一致：" + path.relative(ROOT, targetFile));
      return;
    }
  } else if (checkOnly) {
    failed += 1;
    console.error("[ocr-payload] MISSING  " + label + " 目标不存在：" + path.relative(ROOT, targetFile));
    return;
  }
  fs.mkdirSync(path.dirname(targetFile), { recursive: true });
  fs.copyFileSync(sourceFile, targetFile);
  copied += 1;
  bytes += sourceSize;
  console.log("[ocr-payload] COPY   " + label + " from=" + path.relative(ROOT, sourceFile)
    + " sha256=" + sourceDigest.slice(0, 16) + " bytes=" + sourceSize
    + (targetFile.endsWith(".so") ? " abi=" + elfMachine(targetFile) : ""));
}

for (const entry of LIBS) {
  const source = firstExisting(entry.candidates);
  if (!source) {
    console.error("[ocr-payload] 来源缺失：" + entry.name + "（试过 " + entry.candidates.length + " 个路径）");
    for (const candidate of entry.candidates) console.error("[ocr-payload]   tried: " + candidate);
    failed += 1;
    continue;
  }
  console.log("[ocr-payload] source-chain " + entry.name + " -> " + path.relative(ROOT, source));
  place(source, path.join(OUT, entry.to), entry.name);
}

for (const entry of MODELS) {
  const sourceDir = firstExisting(entry.candidates);
  if (!sourceDir) {
    console.error("[ocr-payload] 来源缺失：OCR 模型目录（试过 " + entry.candidates.length + " 个路径）");
    failed += 1;
    continue;
  }
  const files = walk(sourceDir, []);
  for (const file of files) {
    const relative = path.relative(sourceDir, file);
    place(file, path.join(OUT, entry.to, relative), path.join(entry.to, relative));
  }
}

console.log("[ocr-payload] done mode=" + (checkOnly ? "check" : "copy")
  + " copied=" + copied + " kept=" + skipped + " failed=" + failed + " bytes=" + bytes);
process.exit(failed === 0 ? 0 : 1);