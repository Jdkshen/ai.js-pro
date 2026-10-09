// @engine quickjs
/**
 * smaps-probe2.js —— 内存诊断探针 v2（跑在应用进程内部）
 *
 * v1 的坑：files.read() 对 procfs 文件读出空串（proc 文件 stat 长度=0，普通读取器按长度预读 ⇒ 空）。
 * v2 改用 files.open(path,'r') + readLine() 流式逐行读（回归测试里验证过的接口），边读边统计，不落大数组。
 *
 * 跑法（活进程注入，不走 ScriptIntents 登记）：
 *   adb shell am start -n com.jdkshen.aijspro/com.jdkshen.aijspro.external.shortcut.ShortcutActivity \
 *     --es path /sdcard/xiangqi-p4/proj/tools/smaps-probe2.js
 *
 * 输出：/sdcard/smaps-summary.txt（status 关键项 + 块数/总量 + by-name top15 + 最大块 top25 + rss 分桶）
 */

var OUT = "/sdcard/smaps-summary.txt";
var RESUME_XML = "/data/data/com.jdkshen.aijspro/shared_prefs/external_run_resume.xml";
var PANEL_PATH = "/sdcard/xiangqi-p4/proj/悬浮窗运行与测试.js";

var out = [];
function put(s) { out.push(s); }
function trim2(s) { return s.replace(/^\s+|\s+$/g, ""); }
function toInt(s) { return parseInt(s.replace(/[^0-9]/g, ""), 10) || 0; }

try {
    var xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
        "    <boolean name=\"active\" value=\"true\" />\n" +
        "    <string name=\"path\">" + PANEL_PATH + "</string>\n" +
        "    <long name=\"started_at\" value=\"" + Date.now() + "\" />\n</map>\n";
    files.write(RESUME_XML, xml);
    put("resume-xml-ok");
} catch (e) { put("resume-xml-FAILED " + e); }

function openProc(path) {
    try {
        var f = files.open(path, "r");
        if (!f) put("open-null " + path);
        return f;
    } catch (e) { put("open-FAILED " + path + " " + e); return null; }
}

// ② /proc/self/status
try {
    var fs2 = openProc("/proc/self/status");
    if (fs2 && typeof fs2.readline === "function") {
        var line, cnt = 0;
        while (cnt < 400) {
            line = fs2.readline();
            if (line === null || line === undefined) break;
            if (/^Vm/.test(line)) put("[status] " + trim2(line));
            cnt++;
        }
    } else if (fs2) {
        put("[status] no-readline " + typeof fs2.readline);
    }
    if (fs2) { try { fs2.close(); } catch (e2) {} }
} catch (e) { put("status-FAILED " + e); }

// ③ /proc/self/smaps 流式解析
try {
    var f3 = openProc("/proc/self/smaps");
    if (f3 && typeof f3.readline === "function") {
        var blocks = 0, totalSize = 0, totalRss = 0;
        var byName = {};
        var top = [];
        var bCount = [0, 0, 0, 0, 0, 0], bRss = [0, 0, 0, 0, 0, 0];
        var BNAMES = [">=128MB", "32-128MB", "8-32MB", "1-8MB", "128KB-1MB", "<128KB"];

        function bucketOf(r) {
            if (r >= 131072) return 0;
            if (r >= 32768) return 1;
            if (r >= 8192) return 2;
            if (r >= 1024) return 3;
            if (r >= 128) return 4;
            return 5;
        }
        function takeTop(r, s, n) {
            if (r <= 0) return;
            if (top.length < 25) {
                top.push({ r: r, s: s, n: n });
            } else if (r > top[top.length - 1].r) {
                top[top.length - 1] = { r: r, s: s, n: n };
            } else { return; }
            top.sort(function (a, b) { return b.r - a.r; });
        }

        var curSize = 0, curRss = -1, curName = "", started = false;
        function flushBlock() {
            if (!started) return;
            var rr = curRss > 0 ? curRss : 0;
            blocks++;
            totalSize += curSize;
            totalRss += rr;
            var nm = curName === "" ? "(anon)" : curName;
            var e = byName[nm];
            if (!e) { e = byName[nm] = { c: 0, r: 0 }; }
            e.c++; e.r += rr;
            takeTop(rr, curSize, nm);
            var b = bucketOf(rr);
            bCount[b]++; bRss[b] += rr;
            curSize = 0; curRss = -1; curName = ""; started = false;
        }

        var nLines = 0, l2;
        while (nLines < 3000000) {
            l2 = f3.readline();
            if (l2 === null || l2 === undefined) break;
            nLines++;
            if (l2.indexOf("Size:") === 0) { curSize = toInt(l2.substring(5)); }
            else if (l2.indexOf("Rss:") === 0) { curRss = toInt(l2.substring(4)); }
            else if (l2.indexOf("Name:") === 0) { curName = trim2(l2.substring(5)); }
            else if (/^[0-9a-f]+-[0-9a-f]+ /.test(l2)) { flushBlock(); started = true; }
        }
        flushBlock();
        try { f3.close(); } catch (e3) {}

        put("[smaps] lines=" + nLines + " blocks=" + blocks +
            " totalSizeKB=" + totalSize + " totalRssKB=" + totalRss);

        var names = [];
        for (var k in byName) { if (byName.hasOwnProperty(k)) names.push(k); }
        names.sort(function (a, b) { return byName[b].r - byName[a].r; });
        put("[smaps] --- by-name top 15 (rss sum / blocks / name) ---");
        for (var i = 0; i < names.length && i < 15; i++) {
            put("  " + byName[names[i]].r + "KB / " + byName[names[i]].c + " / " + names[i]);
        }

        put("[smaps] --- top 25 blocks by rss ---");
        for (var j = 0; j < top.length; j++) {
            put("  rss=" + top[j].r + "KB size=" + top[j].s + "KB  " + top[j].n);
        }

        put("[smaps] --- rss buckets (blocks / rssKB) ---");
        for (var b2 = 0; b2 < 6; b2++) {
            if (bCount[b2] > 0) put("  " + BNAMES[b2] + ": " + bCount[b2] + " / " + bRss[b2]);
        }
    } else if (f3) {
        put("[smaps] no-readline " + typeof f3.readline);
    }
} catch (e) { put("smaps-FAILED " + e); }

try {
    files.write(OUT, out.join("\n") + "\n");
    console.log("[smaps-probe] done lines=" + out.length);
} catch (e) {
    console.log("[smaps-probe] write FAILED: " + e);
}
