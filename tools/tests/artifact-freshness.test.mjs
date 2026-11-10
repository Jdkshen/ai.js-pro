// tools/tests/artifact-freshness.test.mjs —— 构件新鲜度自检的三态测试（task-88 ③）
//
// 用临时目录 + 假 mtime 覆盖 FRESH / STALE(template) / STALE(aot)，不依赖真实大文件、不跑 gradle。
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, utimesSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..", "..");
const CHECKER = join(ROOT, "tools", "check-artifact-freshness.ps1");

const OLD = new Date("2026-01-01T00:00:00Z");
const NEW = new Date("2026-06-01T00:00:00Z");

function makeRepo(options) {
    const root = mkdtempSync(join(tmpdir(), "freshness-"));
    const write = (relative, time, body) => {
        const full = join(root, relative);
        mkdirSync(dirname(full), { recursive: true });
        writeFileSync(full, body === undefined ? "x" : body);
        utimesSync(full, time, time);
    };
    write("modules/engine/src/main/java/com/demo/Engine.kt", options.sourceTime, "class SjbcApi\n");
    write("apps/inrt/src/main/java/com/demo/Inrt.java", OLD, "class Inrt {}\n");
    write("apps/inrt/build.gradle", OLD, "// gradle\n");
    write("flutter-ui/lib/project_page.dart", options.sourceTime, "const t = 'zhongwen wengan';\n");
    write("flutter-ui/pubspec.yaml", OLD, "name: flutter_ui\n");
    write("apps/app/src/main/assets/template.apk", options.templateTime, "PK fake template");
    write("output/flutter-poc/payload/jniLibs/arm64-v8a/libapp.so", options.payloadTime, "fake aot");
    return root;
}

// 本机可能只有 Windows PowerShell 5.1（没有 pwsh 7）⇒ 自动挑一个可用的解释器。
function resolveShell() {
    for (const candidate of ["pwsh", "powershell"]) {
        try {
            execFileSync(candidate, ["-NoProfile", "-Command", "exit 0"], { stdio: "ignore" });
            return candidate;
        } catch (error) {
            if (error && error.status === 0) { return candidate; }
        }
    }
    throw new Error("找不到 pwsh/powershell");
}
const SHELL = resolveShell();

function runChecker(root, extraArgs) {
    const args = ["-NoProfile", "-File", CHECKER, "-Root", root, "-SkipDeep"].concat(extraArgs || []);
    try {
        return { stdout: execFileSync(SHELL, args, { encoding: "utf8" }), code: 0 };
    } catch (error) {
        if (error && error.stdout !== undefined && error.stdout !== null) {
            return { stdout: String(error.stdout), code: error.status === undefined || error.status === null ? 1 : error.status };
        }
        throw error;
    }
}

let checks = 0;
function check(name, run) { run(); checks++; console.log("PASS " + name); }

check("FRESH：模板与 AOT 都不旧于源码 → exit 0 且两行 FRESH", () => {
    const root = makeRepo({ templateTime: NEW, payloadTime: NEW, sourceTime: OLD });
    const result = runChecker(root);
    assert.equal(result.code, 0, result.stdout);
    assert.match(result.stdout, /FRESH\(kind=template/);
    assert.match(result.stdout, /FRESH\(kind=aot/);
    assert.doesNotMatch(result.stdout, /STALE/);
    rmSync(root, { recursive: true, force: true });
});

check("STALE(template)：模板比引擎源码旧 → exit 1，且给出先跑 build-slim-template.ps1 的指引", () => {
    const root = makeRepo({ templateTime: OLD, payloadTime: NEW, sourceTime: NEW });
    const result = runChecker(root);
    assert.equal(result.code, 1, result.stdout);
    assert.match(result.stdout, /STALE\(kind=template/);
    assert.match(result.stdout, /build-slim-template\.ps1/);
    assert.doesNotMatch(result.stdout, /STALE\(kind=aot/);
    rmSync(root, { recursive: true, force: true });
});

check("STALE(aot)：payload 比 flutter-ui 源码旧 → exit 1，且给出加 -RebuildAot 的指引", () => {
    const root = makeRepo({ templateTime: NEW, payloadTime: OLD, sourceTime: NEW });
    const result = runChecker(root);
    assert.equal(result.code, 1, result.stdout);
    assert.match(result.stdout, /STALE\(kind=aot/);
    assert.match(result.stdout, /-RebuildAot/);
    assert.doesNotMatch(result.stdout, /STALE\(kind=template/);
    rmSync(root, { recursive: true, force: true });
});

check("JSON 形态：stale=true 且 checks 能定位到 aot", () => {
    const root = makeRepo({ templateTime: NEW, payloadTime: OLD, sourceTime: NEW });
    const result = runChecker(root, ["-Json"]);
    const parsed = JSON.parse(result.stdout);
    assert.equal(parsed.stale, true);
    assert.ok(parsed.checks.some((c) => c.kind === "aot" && c.state === "STALE"), result.stdout);
    rmSync(root, { recursive: true, force: true });
});

console.log("RESULT " + checks + " artifact-freshness checks passed");
