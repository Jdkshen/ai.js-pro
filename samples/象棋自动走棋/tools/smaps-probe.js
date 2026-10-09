// @engine quickjs
/**
 * smaps-probe.js —— 内存诊断探针（跑在应用进程内部）
 *
 * 目的：4.9GB+ 的 Native 内存到底是「几千个小块（图片/帧）」还是「几十个大块（引擎表）」？
 * 做法：脚本在**应用进程内**读 /proc/self/smaps，按块统计 Size/Rss/Name，写摘要到 /sdcard。
 *
 * ⚠️ 本脚本经 RunIntentActivity 拉起时会被 ExternalRunResume 登记为「上次运行」——
 *    脚本结束前会把共享的 SharedPreferences XML **直接改写回面板脚本**（应用写自己的私有目录），
 *    防止下一次进程被杀时自动重拉成探针。跑完后建议再用 run-as 复核一遍该 XML。
 *
 * 跑法：
 *   adb shell am start -n com.jdkshen.aijspro/com.jdkshen.aijspro.external.open.RunIntentActivity \
 *     --es path /sdcard/xiangqi-p4/proj/tools/smaps-probe.js
 */

var OUT = "/sdcard/smaps-summary.txt";
var RESUME_XML = "/data/data/com.jdkshen.aijspro/shared_prefs/external_run_resume.xml";
var PANEL_PATH = "/sdcard/xiangqi-p4/proj/悬浮窗运行与测试.js";

var outLines = [];
function put(s) { outLines.push(s); }

// ① 先把「断点续跑」登记改回面板（本探针的启动会把它登记成自己）
try {
    var xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
        "<map>\n" +
        "    <boolean name=\"active\" value=\"true\" />\n" +
        "    <string name=\"path\">" + PANEL_PATH + "</string>\n" +
        "    <long name=\"started_at\" value=\"" + Date.now() + "\" />\n" +
        "</map>\n";
    files.write(RESUME_XML, xml);
    put("resume-xml-rewritten=panel");
} catch (e) {
    put("resume-xml-rewrite-FAILED: " + e);
}

// ② 读自己的 status
try {
    var st = files.read("/proc/self/status");
    st.split("\n").forEach(function (line) {
        if (/^Vm(Peak|Size|RSS|HWM|Data|Stk|Swap)/.test(line)) put("[status] " + line.trim());
    });
} catch (e) {
    put("status-read-FAILED: " + e);
}

// ③ 读 smaps 并按块汇总
try {
    var text = files.read("/proc/self/smaps");
    put("[smaps] bytes=" + text.length);

    var entries = [];
    var size = 0, rss = 0, name = "";
    function flush() {
        if (size > 0 || rss > 0 || name !== "") entries.push({ s: size, r: rss, n: name });
        size = 0; rss = 0; name = "";
    }
    var n = text.length, lineStart = 0;
    for (var i = 0; i <= n; i++) {
        var atEnd = (i === n);
        if (atEnd || text.charCodeAt(i) === 10) {
            var line = text.substring(lineStart, i);
            lineStart = i + 1;
            if (line.indexOf("Size:") === 0) {
                size = parseInt(line.substring(5).replace(/[^0-9]/g, ""), 10) || 0;
            } else if (line.indexOf("Rss:") === 0) {
                rss = parseInt(line.substring(4).replace(/[^0-9]/g, ""), 10) || 0;
            } else if (line.indexOf("Name:") === 0) {
                name = line.substring(5).trim();
            } else if (/^[0-9a-f]+-[0-9a-f]+ /.test(line)) {
                flush();
            }
        }
    }
    flush();

    var totalRss = 0, totalSize = 0;
    entries.forEach(function (e) { totalRss += e.r; totalSize += e.s; });
    put("[smaps] blocks=" + entries.length + " totalSizeKB=" + totalSize + " totalRssKB=" + totalRss);

    // 按名字归类
    var byName = {};
    entries.forEach(function (e) {
        var key = e.n === "" ? "(anon-no-name)" : e.n;
        if (!byName[key]) byName[key] = { c: 0, r: 0 };
        byName[key].c++; byName[key].r += e.r;
    });
    var names = Object.keys(byName).sort(function (a, b) { return byName[b].r - byName[a].r; });
    put("[smaps] --- by-name top 15 ---");
    names.slice(0, 15).forEach(function (k) {
        put("  " + byName[k].r + "KB rss / " + byName[k].c + " blocks  " + k);
    });

    // 大块列表
    var sorted = entries.slice().sort(function (a, b) { return b.r - a.r; });
    put("[smaps] --- top 25 blocks by rss ---");
    sorted.slice(0, 25).forEach(function (e) {
        put("  rss=" + e.r + "KB size=" + e.s + "KB  " + (e.n || "(anon)"));
    });

    // 尺寸分桶（只看 rss>0 的块）
    var buckets = [
        [131072, Infinity, ">=128MB"],
        [32768, 131072, "32-128MB"],
        [8192, 32768, "8-32MB"],
        [1024, 8192, "1-8MB"],
        [128, 1024, "128KB-1MB"],
        [0, 128, "<128KB"]
    ];
    put("[smaps] --- rss buckets ---");
    buckets.forEach(function (b) {
        var c = 0, r = 0;
        entries.forEach(function (e) {
            if (e.r > 0 && e.r >= b[0] && e.r < b[1]) { c++; r += e.r; }
        });
        if (c > 0) put("  " + b[2] + ": " + c + " blocks, rss " + r + "KB");
    });

    // 只看 libc_malloc 的尺寸分布（谁在大分配）
    var mall = entries.filter(function (e) { return e.n.indexOf("libc_malloc") >= 0; });
    var mallSorted = mall.slice().sort(function (a, b) { return b.r - a.r; });
    put("[smaps] --- anon:libc_malloc top 15 (" + mall.length + " blocks total) ---");
    mallSorted.slice(0, 15).forEach(function (e) {
        put("  rss=" + e.r + "KB size=" + e.s + "KB");
    });
} catch (e) {
    put("smaps-read-FAILED: " + e);
}

// ④ 落盘
try {
    files.write(OUT, outLines.join("\n") + "\n");
    console.log("[smaps-probe] done, wrote " + OUT);
} catch (e) {
    console.log("[smaps-probe] write FAILED: " + e);
}
