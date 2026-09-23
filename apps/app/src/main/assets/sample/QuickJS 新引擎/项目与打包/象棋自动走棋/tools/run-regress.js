// @engine quickjs
/**
 * 场景回归入口（**PC 与真机同一份**）。
 *
 * PC：
 *   node tools/run-regress.js                 # 全部场景 + legacy 套件（self-check / 格子契约）
 *   node tools/run-regress.js --only king     # 只跑名字/标签里含 "king" 的场景
 *   node tools/run-regress.js --no-legacy     # 只跑场景（快）
 * 真机：
 *   adb push … → am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity \
 *       --es path "/sdcard/xiangqi-p4/proj/tools/run-regress.js"
 *   （设备上没有子进程 ⇒ 只跑场景，legacy 套件请在 PC 上跑；报告会落到 /sdcard/xiangqi-p4/regress/）
 *
 * 日志前缀统一 `[regress]`，最后一行是机器可读的汇总：
 *   `[regress] REGRESS OK passed=7 failed=0 skipped=0` / `REGRESS FAILED …`（PC 上以退出码反映）
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            // ⚠️ PC（Node）上是反斜杠、真机（QuickJS）是正斜杠 ⇒ 先统一再剥掉 /tools
            return String(__dirname).replace(/\\/g, "/").replace(/\/tools$/, "");
        }
    } catch (ignore) {
        // 忽略
    }
    return ".";
}());

const ENV = (function () {
    try {
        if (typeof process !== "undefined" && process.versions && process.versions.node) {
            return "node";
        }
    } catch (ignore) {
        // 忽略
    }
    return "quickjs";
}());

function load(relative) {
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[regress] " + text);
    }
}

function stamp() {
    try {
        return new Date().toISOString().replace(/[:.]/g, "-");
    } catch (ignore) {
        return "run";
    }
}

/** PC 专有：跑一个 legacy 套件（它们自己会 `process.exit`，所以只能开子进程） */
function runLegacy(relative, expect, label) {
    const absolute = (BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
    try {
        const childProcess = require("child_process");
        const output = childProcess.execFileSync(process.execPath, [absolute], {
            encoding: "utf8",
            maxBuffer: 64 * 1024 * 1024
        });
        const ok = String(output).indexOf(expect) >= 0;
        const matched = /(SELF_CHECK OK[^\n]*|CONTRACT PASS[^\n]*)/.exec(String(output));
        log((ok ? "✓" : "✗") + " legacy/" + label + "：" + (matched ? matched[1] : "（没看到期望的成功标记）"));
        return { ok: ok, label: label };
    } catch (error) {
        const output = String((error && error.stdout) || "");
        const tail = output ? output.slice(-500) : String((error && error.message) || error);
        log("✗ legacy/" + label + " 失败：" + tail.replace(/\s+/g, " "));
        return { ok: false, label: label };
    }
}

/**
 * 把整份报告写成文本。
 * ⚠️ **必须由 Node/QuickJS 自己写**：PowerShell 的 `*>` 重定向会把子进程的 UTF-8 输出
 *    按 GBK 误解再存成 UTF-16 ⇒ 日志全成乱码（2026-09-20 踩过）。所以框架自己落盘。
 *   · 真机：`/sdcard/xiangqi-p4/regress/<时间戳>.txt`；
 *   · PC：**默认不落盘**（`assets/sample/**` 是打包内容，不能随手丢文件），
 *     要留档就加 `--report <路径>`（也可以是 `.artifacts/xxx.txt`）。
 */
function writeReport(text, target) {
    if (ENV !== "node") {
        try {
            if (typeof files !== "undefined" && files.write) {
                const dir = "/sdcard/xiangqi-p4/regress";
                if (typeof files.ensureDir === "function") {
                    files.ensureDir(dir + "/");
                }
                const path = dir + "/" + stamp() + ".txt";
                files.write(path, text);
                log("报告：" + path);
            }
        } catch (ignore) {
            // 写不下去就算了，日志里已经有全部结果
        }
        return;
    }
    if (!target) {
        log("（PC 默认不写文件；要留档就加 --report .artifacts/regress.txt）");
        return;
    }
    try {
        const fs = require("fs");
        const path = require("path");
        const dir = path.dirname(target);
        if (dir && dir !== ".") {
            fs.mkdirSync(dir, { recursive: true });
        }
        fs.writeFileSync(target, text, "utf8");
        log("报告：" + path.resolve(target));
    } catch (error) {
        log("报告写不出去（" + String((error && error.message) || error) + "），看上面日志即可");
    }
}

function main() {
    const startedAt = Date.now();
    const argv = (ENV === "node" && typeof process !== "undefined") ? process.argv.slice(2) : [];
    let only = null;
    let withLegacy = true;
    let reportTarget = null;
    for (let i = 0; i < argv.length; i++) {
        const one = argv[i];
        if (one === "--no-legacy") {
            withLegacy = false;
        } else if (one === "--only") {
            only = argv[i + 1] || null;
        } else if (one.indexOf("--only=") === 0) {
            only = one.slice(7);
        } else if (one === "--report") {
            reportTarget = argv[i + 1] || null;
        } else if (one.indexOf("--report=") === 0) {
            reportTarget = one.slice(9);
        }
    }

    const harness = load("tools/regress/harness.js").createHarness();
    const registry = load("tools/regress/index.js");
    for (let i = 0; i < registry.cases.length; i++) {
        const path = registry.cases[i];
        harness.register(load(path), path);
    }

    log("环境 " + ENV + "｜已注册场景 " + harness.count() + " 个" + (only ? "（只跑含「" + only + "」的）" : ""));
    const lines = [];
    const record = function (text) {
        lines.push(text);
        log(text);
    };

    const report = harness.runAll({
        env: ENV,
        load: load,
        only: only,
        onCase: function (one) {
            const mark = one.ok ? "✓" : (one.skipped ? "○" : "✗");
            record(mark + " " + one.name + "（断言 " + one.assertions + "，用时 " + one.ms + "ms）" +
                (one.skipped ? "｜跳过：" + one.skipped : ""));
            for (let i = 0; i < one.failures.length; i++) {
                record("    ✗ " + one.failures[i]);
            }
            if (one.error) {
                record("    ✗ 抛异常：" + one.error);
            }
            for (let i = 0; i < one.logs.length; i++) {
                record("    · " + one.logs[i]);
            }
        }
    });

    let legacyFailed = 0;
    if (!withLegacy) {
        record("○ legacy 套件：本次用 --no-legacy 跳过");
    } else if (ENV !== "node") {
        record("○ legacy 套件（self-check / 格子契约）：设备上没有子进程，请在 PC 上跑");
    } else {
        const legacy = [
            runLegacy("tools/self-check.js", "SELF_CHECK OK", "self-check"),
            runLegacy("tools/test-cells-contract.js", "CONTRACT PASS", "格子契约")
        ];
        for (let i = 0; i < legacy.length; i++) {
            if (!legacy[i].ok) {
                legacyFailed += 1;
            }
        }
    }

    const failed = report.failed + legacyFailed;
    record("汇总：场景 通过 " + report.passed + " / 失败 " + report.failed + " / 跳过 " + report.skipped +
        "｜legacy 失败 " + legacyFailed + "｜总用时 " + Math.max(report.ms, Date.now() - startedAt) + "ms");
    record("环境 " + ENV + "｜场景 " + harness.count() + " 个");
    writeReport(lines.join("\n") + "\n", reportTarget);

    if (ENV === "node") {
        log(failed
            ? "REGRESS FAILED passed=" + report.passed + " failed=" + failed + " skipped=" + report.skipped
            : "REGRESS OK passed=" + report.passed + " failed=0 skipped=" + report.skipped);
        if (typeof process !== "undefined" && process.exit) {
            process.exit(failed ? 1 : 0);
        }
        return;
    }
    log(failed
        ? "REGRESS FAILED passed=" + report.passed + " failed=" + failed + " skipped=" + report.skipped
        : "REGRESS OK passed=" + report.passed + " failed=0 skipped=" + report.skipped);
}

main();
