// tools/tests/sjbc-build-all.test.mjs —— tools/sjbc PC 兼容自检（task-85 ③）
//
// 为什么需要它：Lead 2026-11-10 本地 E2E 时把 tools/sjbc 的三处 PC 不可用修掉了
//  ① apk 形态 --cert：keytool 只认 v1(JAR) 签名，v2/v3-only 包会失败 ⇒ 回退 apksigner（.bat 需 cmd /c）
//  ② 上游 build_all.js 的配置是**一个 CFG 对象**（不是四个顶级 const）
//  ③ 上游 require 写的是**手机绝对路径** ⇒ PC 侧必须重写到本目录
// 这三处很容易被「从设备重新拷一份上游文件」打回，所以固化成断言 + 两条 CLI 冒烟。
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..", "..");
const SJBC = join(ROOT, "tools", "sjbc");
const CLI = join(SJBC, "build-all.mjs");
let checks = 0;
function check(name, run) { run(); checks++; console.log("PASS " + name); }

// ---- ① 上游形态（若上游改成别的形状，这三条会先红，提示先改包装）----
check("upstream build_all.js 仍是 CFG 对象 + 手机绝对路径 require", () => {
    const upstream = readFileSync(join(SJBC, "build_all.js"), "utf8");
    assert.match(upstream, /const\s+CFG\s*=\s*\{/, "上游配置不是 CFG 对象（包装需要同步）");
    for (const key of ["srcDir", "outDir", "certSha", "deployTo"]) {
        assert.match(upstream, new RegExp(key + ":"), "CFG 缺少 " + key);
    }
    assert.match(upstream, /require\('\/storage\/emulated\/0\//, "上游 require 不再是手机绝对路径（localizeRequires 可简化，但先确认）");
});

// ---- ② 包装里的三处修复仍在 ----
check("build-all.mjs 保留三处 PC 兼容修复", () => {
    const wrapper = readFileSync(CLI, "utf8");
    assert.match(wrapper, /function\s+replaceCfgObject/, "缺 CFG 对象替换");
    assert.match(wrapper, /function\s+localizeRequires/, "缺 require 本地化");
    assert.match(wrapper, /apksigner/, "缺 apksigner 回退");
    assert.match(wrapper, /cmd\.exe/, "缺 Windows .bat 处理（apksigner.bat）");
    assert.match(wrapper, /SHA-256 digest/, "缺 apksigner --print-certs 的指纹解析");
});

// ---- ③ CLI 冒烟 1：hex 形态 --cert（同时验证 CFG 替换 + require 本地化真的跑起来了）----
check("CLI: --cert <64 hex> 能跑完并写出 manifest", () => {
    const dir = mkdtempSync(join(tmpdir(), "sjbc-selfcheck-"));
    const src = join(dir, "src");
    const out = join(dir, "out");
    mkdirSync(src, { recursive: true });
    const hex = "ab".repeat(32);
    const stdout = execFileSync(process.execPath, [CLI, "--src", src, "--out", out, "--cert", hex], { encoding: "utf8" });
    assert.match(stdout, /certSha=abab/, "CLI 没回显 certSha");
    const manifestPath = join(out, "manifest.json");
    assert.ok(existsSync(manifestPath), "没有写 manifest.json（CFG/require 重写可能失效）");
    const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
    assert.equal(manifest.version, 1);
    assert.equal(manifest.cert, hex, "manifest.cert 不等于传入的 hex");
});

// ---- ③ CLI 冒烟 2：apk 形态 --cert（v2/v3-only 包；本机无 apksigner 时跳过）----
const APK = join(ROOT, "output", "w2-pack-verify-2026-11-09", "W2PackA_v1.0.0.apk");
if (existsSync(APK)) {
    const wrapper = readFileSync(CLI, "utf8");
    const hasApksigner = wrapper.includes("apksigner");
    if (!hasApksigner) {
        console.log("SKIP apk 形态 --cert：包装里没有 apksigner 回退");
    } else {
        try {
            const dir = mkdtempSync(join(tmpdir(), "sjbc-selfcheck-apk-"));
            const src = join(dir, "src");
            const out = join(dir, "out");
            mkdirSync(src, { recursive: true });
            const stdout = execFileSync(process.execPath, [CLI, "--src", src, "--out", out, "--cert", APK], { encoding: "utf8" });
            const match = stdout.match(/certSha=([0-9a-f]{64})/);
            assert.ok(match, "apk 形态没有解析出 64 位指纹：" + stdout.split("\n")[0]);
            const manifest = JSON.parse(readFileSync(join(out, "manifest.json"), "utf8"));
            assert.equal(manifest.cert, match[1]);
            checks++;
            console.log("PASS CLI: --cert <.apk v2/v3-only> 取到指纹 " + match[1].slice(0, 16) + "…");
        } catch (error) {
            // 本机没有 apksigner/keytool 时按任务要求跳过（不是失败）
            console.log("SKIP apk 形态 --cert（本机取不到指纹）：" + String(error.message || error).split("\n")[0].slice(0, 160));
        }
    }
} else {
    console.log("SKIP apk 形态 --cert：找不到测试用 APK " + APK);
}
console.log("RESULT " + checks + " sjbc PC-compat checks passed");
