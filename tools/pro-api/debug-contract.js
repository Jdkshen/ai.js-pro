// @engine rhino
// $debug 行为验收（对照 335 页 PDF §2.9 第 81~82 页）
// 跑法见 tools/pro-api/README.md：push 后 am start 打开，日志里看 OK/FAIL 与 DEBUG_SUMMARY。
// 注意：两次 dumpHprof 会各写一个几十 MB 的 hprof（脚本结束前会删掉），耗时比别的契约脚本长。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

function exists(path) {
    return $files.exists(path);
}

function sizeOf(path) {
    return new java.io.File(path).length();
}

function remove(path) {
    if (exists(path)) { $files.remove(path); }
}

// 1) getStackTrace：JS 异常
var jsTrace = '';
try {
    undefined_variable_for_debug_contract;
} catch (e) {
    jsTrace = $debug.getStackTrace(e);
}
check("getStackTrace(JS 异常) 是字符串", typeof jsTrace === 'string', true);
check("getStackTrace(JS 异常) 含 ReferenceError", jsTrace.indexOf('ReferenceError') >= 0, true);
check("getStackTrace(JS 异常) 含脚本名", jsTrace.indexOf('current.js') >= 0, true);

// 2) getStackTrace：Java 异常
var javaTrace = '';
try {
    new java.io.FileInputStream('/sdcard/definitely-not-here-debug-contract');
} catch (e) {
    javaTrace = $debug.getStackTrace(e);
}
check("getStackTrace(Java 异常) 含 FileNotFoundException", javaTrace.indexOf('FileNotFoundException') >= 0, true);

// 3) 内存泄露检测（文档：图片没有手动 recycle 会被打印出来）
check("默认开启检测（运行时）", $debug.isMemoryLeakDetectionEnabled(), true);
$debug.setMemoryLeakDetectionEnabled(true);
var before = $debug.getMemoryLeakCount();
for (var i = 0; i < 20; i++) {
    (function () {
        var leaked = new Image(32, 32);   // 故意不 recycle
    })();
}
for (var j = 0; j < 20; j++) {
    (function () {
        var ok = new Image(32, 32);
        ok.recycle();                    // 正常回收，不应被算作泄露
    })();
}
$debug.gc();
// ART 的 System.gc() 只是"建议"，小对象往往要等内存压力才真被回收：
// 这里额外分配一块大数组逼出一次 GC，再轮询等引用队列（gc() 每次都会 drain）。
var after = before;
for (var k = 0; k < 10 && after <= before; k++) {
    try {
        var pressure = java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, 16 * 1024 * 1024);
        pressure = null;
    } catch (e) {
        // 分配失败也无所谓，继续等
    }
    sleep(300);
    $debug.gc();
    after = $debug.getMemoryLeakCount();
}
check("泄漏的图片被报告出来", after > before, true);
check("已 recycle 的图片不计入", after <= 25, true);   // 只有 20 张该被报，留点余量

// 4) gc 可调用（文档：只是"建议"回收）
$debug.gc();
check("gc 调用后脚本仍活着", true, true);

// 5) dumpHprof
var hprof = $files.join(files.cwd(), "debug-contract.hprof");
remove(hprof);
try {
    var returned = $debug.dumpHprof(hprof);
    check("dumpHprof 返回路径", String(returned) === hprof, true);
} catch (e) {
    check("dumpHprof 返回路径", 'threw: ' + e, hprof);
}
check("dumpHprof 产出文件", exists(hprof), true);
check("dumpHprof 文件非空", sizeOf(hprof) > 0, true);
remove(hprof);

// 6) dumpAndSendHprof（不传参数 → 文档默认 ./dump.hprof.zip）
var defaultZip = $files.join(files.cwd(), "dump.hprof.zip");
remove(defaultZip);
var zipReturned = $debug.dumpAndSendHprof();
check("dumpAndSendHprof 默认路径", String(zipReturned) === defaultZip, true);
check("dumpAndSendHprof 产出 zip", exists(defaultZip), true);
check("dumpAndSendHprof 的 zip 非空", sizeOf(defaultZip) > 0, true);
var zf = $zip.open(defaultZip);
check("产物是有效 zip", zf.isValidZipFile(), true);
var headers = zf.getFileHeaders();
check("zip 里是 hprof 文件", headers.length === 1 && headers[0].getFileName().indexOf('.hprof') > 0, true);
check("临时文件已清理", exists(defaultZip + '.tmp.hprof'), false);
remove(defaultZip);

// 7) 关闭检测
$debug.setMemoryLeakDetectionEnabled(false);
check("可关闭检测", $debug.isMemoryLeakDetectionEnabled(), false);
$debug.setMemoryLeakDetectionEnabled(true);

console.log('DEBUG_SUMMARY|pass=' + pass + ' failed=' + fail);
