#!/usr/bin/env node
// task-131：插件清单（plugins.json）的生成与校验。
//
// 目的：把「公网可拉取的插件清单」做成**单一来源**：
//   plugins/<id>/plugin.json（人写：id/显示名/包名/最小主程序版本/文件名/说明/是否需重启）
//   + project-versions.json（版本号单一来源：<x>PluginVersionName / VersionCode）
//   + 已构建的插件 APK（若存在 ⇒ 填 sizeBytes/sha256；不存在 ⇒ 标 "pending"，仍可出清单）
//   ⇒ plugins.json（机器读：更新源目录里与 update.json 同目录，Release 资产同名）
//
// 用法：
//   node tools/plugins-manifest.mjs --out <path>            生成清单
//   node tools/plugins-manifest.mjs --check <path>          校验清单（含 sha256/大小与 APK 对账，若给了 --apk-dir）
//   node tools/plugins-manifest.mjs --out <path> --json     生成并以 JSON 打印摘要
//
// 快速更新策略（见 docs/reports 设计文档）：客户端**只先拉 plugins.json**（体积几 KB，带 ETag/If-None-Match
// 条件请求；304 即结束），再按「本机缺失 / 版本低于清单」才下载对应 APK —— 不做全量探测。

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import process from "node:process";

const SCHEMA = 1;
const ROOT = process.cwd();
const PLUGINS_DIR = path.join(ROOT, "plugins");
const VERSIONS_FILE = path.join(ROOT, "project-versions.json");

function fail(message) {
  console.error("[plugins-manifest] " + message);
  process.exit(1);
}

function argValue(name, fallback) {
  const index = process.argv.indexOf(name);
  if (index < 0 || index + 1 >= process.argv.length) return fallback;
  return process.argv[index + 1];
}

function hasFlag(name) {
  return process.argv.includes(name);
}

function readJson(file) {
  if (!fs.existsSync(file)) fail("找不到文件：" + file);
  try {
    return JSON.parse(fs.readFileSync(file, "utf8").replace(/^\uFEFF/, ""));
  } catch (error) {
    fail("JSON 解析失败 " + file + "：" + error.message);
  }
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function versionFor(versions, id, suffix) {
  // 约定：id = xiangqi -> xiangqiPluginVersionName；id = apkbuilder -> apkBuilderPluginVersionName
  const camel = id === "apkbuilder" ? "apkBuilder" : id;
  const key = camel + "Plugin" + suffix;
  return versions[key];
}

function descriptorIds() {
  if (!fs.existsSync(PLUGINS_DIR)) fail("找不到 plugins/ 目录");
  return fs.readdirSync(PLUGINS_DIR, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .filter((name) => fs.existsSync(path.join(PLUGINS_DIR, name, "plugin.json")))
    .sort()
    .filter((name) => {
      const descriptor = readJson(path.join(PLUGINS_DIR, name, "plugin.json"));
      if (isDraft(descriptor)) {
        console.warn("[plugins-manifest] 跳过草稿插件：" + name + "（draft=true，不进发布清单）");
        return false;
      }
      // task-131：minAppVersion 必须是**已确定**的值（「第一个带 PluginNativeLoader + $ocr 分发的宿主版本」）。
      // 未定值（null/缺失）就不发布该插件 —— 填一个拍脑袋的下限会让老宿主装到跑不起来的插件。
      if (descriptor.minAppVersion === null || descriptor.minAppVersion === undefined) {
        console.warn("[plugins-manifest] 跳过未定 minAppVersion 的插件：" + name + "（等 W1 真机给值后再发布）");
        return false;
      }
      return true;
    });
}

/** 草稿（draft:true）不进发布清单：版本/契约未定稿的插件不能出现在公网清单里。 */
function isDraft(descriptor) {
  return descriptor.draft === true;
}

function requireFields(descriptor, file) {
  const required = ["id", "displayName", "package", "file", "minAppVersion", "requiresRestart", "notes"];
  const missing = required.filter((key) => descriptor[key] === undefined || descriptor[key] === "");
  if (missing.length > 0) fail(file + " 缺字段：" + missing.join(", "));
  if (!/^[a-z][a-z0-9-]*$/.test(descriptor.id)) fail(file + " 的 id 必须是小写 kebab-case：" + descriptor.id);
  if (!/^[a-zA-Z][a-zA-Z0-9_.]*$/.test(descriptor.package)) fail(file + " 的 package 不合法：" + descriptor.package);
}

function buildManifest() {
  const versions = fs.existsSync(VERSIONS_FILE) ? readJson(VERSIONS_FILE) : {};
  const plugins = descriptorIds().map((dir) => {
    const file = path.join(PLUGINS_DIR, dir, "plugin.json");
    const descriptor = readJson(file);
    requireFields(descriptor, file);
    const versionName = descriptor.version || versionFor(versions, descriptor.id, "VersionName");
    const versionCode = descriptor.versionCode || versionFor(versions, descriptor.id, "VersionCode");
    if (!versionName) fail(file + " 无法确定版本号（project-versions.json 里缺 " + descriptor.id + "PluginVersionName）");
    // 构建产物名常带 -release / -release-unsigned 后缀，而发布资产名是 descriptor.file（客户端按它下载）
    // ⇒ 按顺序找：正式名 -> -release -> -release-unsigned，并回显实际用的文件（上传时要改名成 descriptor.file）。
    const releaseDir = path.join(PLUGINS_DIR, dir, "build/outputs/apk/release");
    const base = descriptor.file.replace(/\.apk$/, "");
    let apkPath = path.join(releaseDir, descriptor.file);
    for (const candidate of [descriptor.file, base + "-release.apk", base + "-release-unsigned.apk"]) {
      const full = path.join(releaseDir, candidate);
      if (fs.existsSync(full)) { apkPath = full; break; }
    }
    if (fs.existsSync(apkPath) && path.basename(apkPath) !== descriptor.file) {
      console.warn("[plugins-manifest] " + descriptor.id + "：用构建产物 " + path.basename(apkPath)
        + " 计算 sha256/大小；上传时必须改名为 " + descriptor.file + "（客户端按 file 字段下载）");
    }
    const entry = {
      id: descriptor.id,
      displayName: descriptor.displayName,
      package: descriptor.package,
      version: versionName,
      versionCode: versionCode === undefined ? null : Number(versionCode),
      minAppVersion: Number(descriptor.minAppVersion),
      file: descriptor.file,
      sizeBytes: null,
      sha256: "pending",
      requiresRestart: Boolean(descriptor.requiresRestart),
      abis: descriptor.abis || [],
      notes: descriptor.notes
    };
    if (fs.existsSync(apkPath)) {
      entry.sizeBytes = fs.statSync(apkPath).size;
      entry.sha256 = sha256(apkPath);
    }
    return entry;
  });
  return { schema: SCHEMA, generatedAt: new Date().toISOString(), plugins: plugins };
}

function checkManifest(file, apkDir) {
  const manifest = readJson(file);
  const problems = [];
  if (manifest.schema !== SCHEMA) problems.push("schema 必须是 " + SCHEMA + "，实际 " + manifest.schema);
  if (!Array.isArray(manifest.plugins) || manifest.plugins.length === 0) problems.push("plugins 必须是非空数组");
  const seen = new Set();
  for (const plugin of manifest.plugins || []) {
    for (const key of ["id", "displayName", "package", "version", "minAppVersion", "file", "sha256", "requiresRestart", "notes"]) {
      if (plugin[key] === undefined) problems.push(plugin.id + " 缺字段 " + key);
    }
    if (seen.has(plugin.id)) problems.push("id 重复：" + plugin.id);
    seen.add(plugin.id);
    if (apkDir) {
      const apk = path.join(apkDir, plugin.file);
      if (!fs.existsSync(apk)) {
        problems.push(plugin.id + " 找不到 APK：" + apk);
      } else {
        const actualSize = fs.statSync(apk).size;
        const actualSha = sha256(apk);
        if (plugin.sizeBytes !== null && plugin.sizeBytes !== actualSize) {
          problems.push(plugin.id + " sizeBytes 不符：清单 " + plugin.sizeBytes + " vs 实际 " + actualSize);
        }
        if (plugin.sha256 !== actualSha) {
          problems.push(plugin.id + " sha256 不符：清单 " + plugin.sha256 + " vs 实际 " + actualSha);
        }
      }
    }
  }
  if (problems.length > 0) {
    console.error("[plugins-manifest] 校验失败（" + problems.length + " 条）：");
    for (const problem of problems) console.error("  - " + problem);
    process.exit(1);
  }
  console.log("[plugins-manifest] 校验通过：" + file + "（" + (manifest.plugins || []).length + " 个插件）");
}

const out = argValue("--out", "");
const check = argValue("--check", "");
const apkDir = argValue("--apk-dir", "");

if (check) {
  checkManifest(check, apkDir);
} else if (out) {
  const manifest = buildManifest();
  fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true });
  fs.writeFileSync(out, JSON.stringify(manifest, null, 2) + "\n", "utf8");
  const summary = manifest.plugins.map((p) => p.id + "@" + p.version + "(" + (p.sha256 === "pending" ? "APK 未构建" : p.sizeBytes + "B") + ")");
  console.log("[plugins-manifest] 已生成 " + out + " -> " + summary.join(", "));
  if (hasFlag("--json")) console.log(JSON.stringify(manifest));
} else {
  console.log("usage: node tools/plugins-manifest.mjs --out <path> [--json]");
  console.log("       node tools/plugins-manifest.mjs --check <path> [--apk-dir <dir>]");
  process.exit(2);
}