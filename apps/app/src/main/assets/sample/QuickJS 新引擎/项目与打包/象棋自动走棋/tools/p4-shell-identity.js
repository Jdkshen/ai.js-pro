// @engine quickjs
/**
 * 应用内 shell 身份 / 注入能力诊断（设备端）。
 *
 * 背景：同一条 `input swipe`，从 adb 跑能点动天天象棋（选中棋子→落子），
 * 在应用里用 `shell(cmd, {shizuku:true})` 跑却 0 像素变化。两者身份都该是 shell(2000)，
 * 所以这里把「到底以什么身份跑、`input` 是成功还是抛异常、/dev/input 能不能碰」打印出来。
 *
 * 输出：/sdcard/xiangqi-p4/shell-identity.json
 */
const OUT = "/sdcard/xiangqi-p4/shell-identity.json";
const TAG = "SHELLID";

function say(text) {
    console.log(TAG + " " + text);
}

const report = {};

function run(label, cmd, options) {
    const opts = Object.assign({ shizuku: true, timeout: 8000 }, options || {});
    const entry = { cmd: cmd, shizuku: opts.shizuku !== false };
    try {
        const r = shell(cmd, opts);
        if (r == null) {
            entry.nullResult = true;
        } else if (typeof r === "number") {
            entry.code = r;
        } else {
            entry.code = r.code;
            entry.output = String(r.output == null ? "" : r.output).slice(0, 500);
            entry.stdout = String(r.stdout == null ? "" : r.stdout).slice(0, 500);
            entry.stderr = String(r.stderr == null ? "" : r.stderr).slice(0, 300);
            entry.result = String(r.result == null ? "" : r.result).slice(0, 500);
            entry.error = String(r.error == null ? "" : r.error).slice(0, 300);
            entry.keys = Object.keys(r).join(",");
        }
    } catch (e) {
        entry.threw = String(e && e.message ? e.message : e).slice(0, 300);
    }
    report[label] = entry;
    say(label + " → " + JSON.stringify(entry).slice(0, 260));
}

function main() {
    run("id_shizuku", "id");
    run("id_normal", "id", { shizuku: false });
    run("whoami_shizuku", "whoami");
    run("input_swipe_echo", "input swipe 199 1501 199 1501 400; echo EXIT=$?");
    run("input_tap_echo", "input tap 199 1501; echo EXIT=$?");
    run("input_duration_help", "input --help 2>&1 | head -5");
    run("ls_dev_input", "ls -l /dev/input/ 2>&1 | head -10");
    run("can_read_input", "cat /dev/input/event0 >/dev/null 2>&1; echo EXIT=$?");
    run("pm_path_shizuku", "pm path moe.shizuku.privileged.api 2>&1 | head -2");

    files.write(OUT, JSON.stringify(report));
    say("已写 " + OUT);
}

try {
    main();
} catch (error) {
    say("崩溃：" + error);
    try {
        files.write(OUT, JSON.stringify(report));
    } catch (e2) { /* 忽略 */ }
}
