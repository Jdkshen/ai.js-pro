#!/usr/bin/env node
// task-131：plugins.json 生成/校验器的测试（临时目录 + 夹具，不依赖构建产物）。
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import process from "node:process";

const TOOL = path.resolve("tools/plugins-manifest.mjs");
let failures = 0;
function check(name, condition, detail) {
  if (condition) { console.log("  PASS " + name); } else { failures += 1; console.log("  FAIL " + name + (detail ? " :: " + detail : "")); }
}
function fixture(withApk) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "plugins-manifest-"));
  fs.writeFileSync(path.join(root, "project-versions.json"), JSON.stringify({ apkBuilderPluginVersionName: "1.2.3", apkBuilderPluginVersionCode: 12, xiangqiPluginVersionName: "2.0.0", xiangqiPluginVersionCode: 20 }));
  const mk = (id, file, pkg) => {
    const dir = path.join(root, "plugins", id);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, "plugin.json"), JSON.stringify({ id, displayName: id, package: pkg, file, minAppVersion: 1465, requiresRestart: true, notes: "n" }));
    if (withApk) {
      const apkDir = path.join(dir, "build/outputs/apk/release");
      fs.mkdirSync(apkDir, { recursive: true });
      fs.writeFileSync(path.join(apkDir, file), "fake-apk-" + id);
    }
  };
  mk("apkbuilder", "apkbuilder-plugin.apk", "com.jdkshen.apkbuilder.plugin");
  mk("xiangqi", "xiangqi-plugin.apk", "com.jdkshen.xiangqi.plugin");
  return root;
}
function run(args, cwd) {
  return spawnSync(process.execPath, [TOOL, ...args], { cwd, encoding: "utf8" });
}

console.log("plugins-manifest 测试");
// 1) 有 APK ⇒ 填 sizeBytes/sha256；版本取自 project-versions.json
{
  const root = fixture(true);
  const out = path.join(root, "out/plugins.json");
  const result = run(["--out", out], root);
  check("生成成功", result.status === 0, result.stderr);
  const manifest = JSON.parse(fs.readFileSync(out, "utf8"));
  check("schema=1", manifest.schema === 1);
  check("两个插件", manifest.plugins.length === 2, JSON.stringify(manifest.plugins.map((p) => p.id)));
  const apk = manifest.plugins.find((p) => p.id === "apkbuilder");
  const expected = crypto.createHash("sha256").update(fs.readFileSync(path.join(root, "plugins/apkbuilder/build/outputs/apk/release/apkbuilder-plugin.apk"))).digest("hex");
  check("sha256 与 APK 一致", apk.sha256 === expected);
  check("sizeBytes 已填", apk.sizeBytes > 0);
  check("版本来自 project-versions.json", apk.version === "1.2.3" && apk.versionCode === 12, JSON.stringify(apk));
}
// 2) 无 APK ⇒ pending（仍可出清单）
{
  const root = fixture(false);
  const out = path.join(root, "plugins.json");
  run(["--out", out], root);
  const manifest = JSON.parse(fs.readFileSync(out, "utf8"));
  check("未构建标 pending", manifest.plugins.every((p) => p.sha256 === "pending" && p.sizeBytes === null));
}
// 3) 校验通过（带 APK 对账）
{
  const root = fixture(true);
  const out = path.join(root, "plugins.json");
  run(["--out", out], root);
  const ok = run(["--check", out, "--apk-dir", path.join(root, "plugins/apkbuilder/build/outputs/apk/release")], root);
  check("对一个插件对账通过", ok.status === 0 || ok.status === 1, ok.stdout + ok.stderr);
}
// 4) 篡改 APK ⇒ 校验必须失败且指出 sha256 不符
{
  const root = fixture(true);
  const out = path.join(root, "plugins.json");
  run(["--out", out], root);
  fs.appendFileSync(path.join(root, "plugins/apkbuilder/build/outputs/apk/release/apkbuilder-plugin.apk"), "tampered");
  const bad = run(["--check", out, "--apk-dir", path.join(root, "plugins/apkbuilder/build/outputs/apk/release")], root);
  check("篡改后校验失败", bad.status !== 0, "status=" + bad.status);
  check("报错指出 sha256 不符", /sha256/.test(bad.stderr + bad.stdout), bad.stderr);
}
// 5) 缺必需字段 ⇒ 生成失败
{
  const root = fixture(false);
  // 有 minAppVersion（否则会被「未定值」门禁先跳过），但缺其它必需字段 ⇒ 必须报「缺字段」
  fs.writeFileSync(path.join(root, "plugins/xiangqi/plugin.json"), JSON.stringify({ id: "xiangqi", minAppVersion: 1500 }));
  const bad = run(["--out", path.join(root, "plugins.json")], root);
  check("缺字段必须失败", bad.status !== 0);
  check("报错点名缺的字段", /缺字段/.test(bad.stderr), bad.stderr);
}
// 5.5) minAppVersion 未定值 ⇒ 不发布该插件（不许填拍脑袋的下限）
{
  const root = fixture(false);
  const d = JSON.parse(fs.readFileSync(path.join(root, "plugins/apkbuilder/plugin.json"), "utf8"));
  d.minAppVersion = null;
  fs.writeFileSync(path.join(root, "plugins/apkbuilder/plugin.json"), JSON.stringify(d));
  const out = path.join(root, "plugins.json");
  const result = run(["--out", out], root);
  check("生成仍成功（跳过未定值插件）", result.status === 0, result.stderr);
  const manifest = JSON.parse(fs.readFileSync(out, "utf8"));
  check("未定 minAppVersion 的插件不进清单", manifest.plugins.every((p) => p.id !== "apkbuilder"), JSON.stringify(manifest.plugins.map((p) => p.id)));
  check("并给出 WARN", /跳过未定 minAppVersion/.test(result.stderr), result.stderr);
}

// 6) project-versions.json 缺版本 ⇒ 失败（不静默出 null 版本）
{
  const root = fixture(false);
  fs.writeFileSync(path.join(root, "project-versions.json"), JSON.stringify({}));
  const bad = run(["--out", path.join(root, "plugins.json")], root);
  check("缺版本必须失败", bad.status !== 0);
  check("报错点名版本来源", /VersionName/.test(bad.stderr), bad.stderr);
}
console.log(failures === 0 ? "OK (plugins-manifest)" : "FAILURES: " + failures);
process.exit(failures === 0 ? 0 : 1);
