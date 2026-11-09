#!/usr/bin/env node
/**
 * tools/sjbc/build-all.mjs —— PC 侧一键包装（task-80 ③）。
 *
 * 设计约束：**不改 SJBC 算法**。本脚本只做两件事：
 *   1) 解析 --src/--out/--cert/--deploy-to/--check；
 *   2) 把 build_all.js 顶部那 4 行配置（srcDir/outDir/certSha/deployTo）替换成本次参数，
 *      写到临时文件后交给 node 执行（原文件一字不改）。
 */
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, existsSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));

function usage(code = 1) {
    console.error("用法: node tools/sjbc/build-all.mjs --src <源码目录> --out <产出目录> --cert <apk|sha256> [--deploy-to <目录>] [--check]");
    process.exit(code);
}

function parseArgs(argv) {
    const args = { deployTo: "", check: false };
    for (let i = 0; i < argv.length; i++) {
        const key = argv[i];
        const value = argv[i + 1];
        if (key === "--src") { args.src = value; i++; }
        else if (key === "--out") { args.out = value; i++; }
        else if (key === "--cert") { args.cert = value; i++; }
        else if (key === "--deploy-to") { args.deployTo = value; i++; }
        else if (key === "--check") { args.check = true; }
        else if (key === "--help" || key === "-h") usage(0);
        else usage(1);
    }
    if (!args.src || !args.out || !args.cert) usage(1);
    return args;
}

/** certSha 只接受两种形态：64 位 hex（直接给指纹）或 .apk（用 keytool 取签名指纹）。 */
function resolveCertSha(cert) {
    const hex = String(cert).trim().toLowerCase();
    if (/^[0-9a-f]{64}$/.test(hex)) {
        return hex;
    }
    const apk = resolve(cert);
    if (!existsSync(apk)) {
        throw new Error("--cert 既不是 64 位 SHA-256，也不是存在的 APK 路径：" + cert);
    }
    // ① keytool -printcert -jarfile：只对**带 v1(JAR) 签名**的包有效；apksigner 出的 v2/v3-only 包会
    //    报「不是签名的 jar 文件」⇒ 必须回退。
    // ② apksigner verify --print-certs：对 v2/v3 有效（我们自己的候选包就是这种）。
    //    注：Lead 2026-11-10 在本地 E2E 时踩到这条（我们的包没有 v1 签名）——**不是**算法改动。
    const keytoolOut = tryExec("keytool", ["-printcert", "-jarfile", apk]);
    const keytoolMatch = keytoolOut && keytoolOut.match(/SHA256:\s*([0-9A-Fa-f:]{64,})/);
    if (keytoolMatch) {
        return keytoolMatch[1].replace(/:/g, "").toLowerCase();
    }
    for (const apksigner of apksignerCandidates()) {
        const out = tryExec(apksigner, ["verify", "--print-certs", apk]);
        const match = out && out.match(/SHA-256 digest:\s*([0-9A-Fa-f]{64})/);
        if (match) {
            return match[1].toLowerCase();
        }
    }
    throw new Error("取不到签名证书 SHA-256：keytool 认为这不是 v1 签名的 jar（v2/v3-only 属正常），"
        + "且 apksigner 不可用或没输出指纹。请直接给 64 位 hex 指纹（--cert <sha256>）。");
}

/** 执行命令，失败/不存在返回 null（不回显 stderr，避免噪音）。 */
function tryExec(file, args) {
    try {
        // Windows 下 .bat/.cmd 不能由 execFileSync 直接执行 ⇒ 走 cmd /c（apksigner.bat 就是这种情况）。
        const isBatch = /\.(bat|cmd)$/i.test(file);
        const cmd = isBatch ? "cmd.exe" : file;
        const cmdArgs = isBatch ? ["/c", file, ...args] : args;
        return execFileSync(cmd, cmdArgs, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
    } catch (error) {
        return null;
    }
}

/** apksigner 候选：PATH → ANDROID_HOME/ANDROID_SDK_ROOT → 常见 build-tools（版本号倒序）。 */
function apksignerCandidates() {
    const list = ["apksigner", "apksigner.bat"];
    const roots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, "C:\\Android", "D:\\Android"]
        .filter(Boolean);
    for (const root of roots) {
        const buildTools = join(root, "build-tools");
        if (!existsSync(buildTools)) continue;
        for (const name of readdirSync(buildTools).sort().reverse()) {
            for (const exe of ["apksigner.bat", "apksigner"]) {
                const candidate = join(buildTools, name, exe);
                if (existsSync(candidate)) list.push(candidate);
            }
        }
    }
    return list;
}

const args = parseArgs(process.argv.slice(2));
const certSha = resolveCertSha(args.cert);
const src = resolve(args.src);
const out = resolve(args.out);
const buildAll = join(HERE, "build_all.js");
let source = readFileSync(buildAll, "utf8");

function replaceConfig(text, name, value) {
    const pattern = new RegExp("(^\\s*(?:const|let|var)\\s+" + name + "\\s*=\\s*)([^;\\n]*)(;?)", "m");
    if (!pattern.test(text)) {
        throw new Error("build_all.js 里找不到配置 " + name + "（上游可能改过结构，请人工核对后更新本包装）");
    }
    return text.replace(pattern, function (_m, head, _old, semi) { return head + JSON.stringify(value) + (semi || ";"); });
}

// 上游 build_all.js 的配置是**一个 CFG 对象**（`const CFG = { srcDir: '…', … }`），不是四个顶级 const；
// 且它的 require 写的是**手机绝对路径**（/storage/emulated/0/脚本/sjbc-tools/*.js）⇒ PC 侧必须把这两处
// 一起重写，否则本地跑不起来（Lead 2026-11-10 本地 E2E 时踩到；**算法/配置语义未改**）。
function replaceCfgObject(text, cfg) {
    const pattern = /const\s+CFG\s*=\s*\{[\s\S]*?\n\};/;
    if (!pattern.test(text)) {
        // 兼容「四个顶级 const」的上游变体
        let migrated = text;
        migrated = replaceConfig(migrated, "srcDir", cfg.srcDir);
        migrated = replaceConfig(migrated, "outDir", cfg.outDir);
        migrated = replaceConfig(migrated, "certSha", cfg.certSha);
        migrated = replaceConfig(migrated, "deployTo", cfg.deployTo);
        return migrated;
    }
    const block = "const CFG = {\n"
        + "  srcDir: " + JSON.stringify(cfg.srcDir) + ",\n"
        + "  outDir: " + JSON.stringify(cfg.outDir) + ",\n"
        + "  certSha: " + JSON.stringify(cfg.certSha) + ",\n"
        + "  deployTo: " + JSON.stringify(cfg.deployTo) + ",\n"
        + "};";
    return text.replace(pattern, block);
}

/** 把手机绝对路径的 require 指到本目录（生成脚本在临时目录里跑 ⇒ 必须用绝对路径）。 */
function localizeRequires(text) {
    return text.replace(/require\('\/storage\/emulated\/0\/[^']*?\/([A-Za-z0-9_.-]+\.js)'\)/g,
        function (_m, file) { return "require(" + JSON.stringify(join(HERE, file)) + ")"; });
}

source = replaceCfgObject(source, { srcDir: src, outDir: out, certSha, deployTo: args.deployTo || "" });
source = localizeRequires(source);

const dir = mkdtempSync(join(tmpdir(), "sjbc-build-"));
const driver = join(dir, "build_all.generated.js");
writeFileSync(driver, source, "utf8");
console.log("sjbc: src=" + src + " out=" + out + " certSha=" + certSha + (args.deployTo ? " deployTo=" + args.deployTo : ""));
console.log("sjbc: 执行 " + driver + "（build_all.js 原文件未改动）");
execFileSync(process.execPath, [driver], { stdio: "inherit", cwd: HERE });
console.log("sjbc: 完成，产物在 " + out + "（.sjbc + manifest.json）");
