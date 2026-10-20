"nodejs";
// Node启动子引擎(execScriptFile)
// engines.execScriptFile 启动一个子 Node 引擎，子脚本写结果文件，父脚本轮询读取。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/10_execScriptFile.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

const engines = require('engines');
try {
    const probeDir = '/sdcard/脚本/_nodeapi_probe_';
    const childPath = probeDir + '/_exec_child.js';
    const childResult = probeDir + '/_exec_child_result.json';
    try { files.mkdir(probeDir); } catch (ignored) { }
    try { if (files.exists(childResult)) files.remove(childResult); } catch (ignored) { }

    // 子脚本自包含：首行引擎指令 + 把 execArgv.arguments 回传。
    files.write(childPath, [
        '"nodejs";',
        '// 由 execScriptFile 示例启动的子引擎',
        'const files = require("files");',
        'const engines = require("engines");',
        'const argv = engines.myEngine().execArgv || {};',
        'files.write("/sdcard/脚本/_nodeapi_probe_/_exec_child_result.json", JSON.stringify({',
        '    ok: true, execArgv: argv, engineId: engines.myEngine().id, at: Date.now()',
        '}));'
    ].join('\n'));

    const execution = engines.execScriptFile(childPath, { arguments: { from: 'execScriptFile示例', n: 7 } });
    const deadline = Date.now() + 10000;
    (function poll() {
        let child = null;
        try {
            if (files.exists(childResult)) {
                child = JSON.parse(files.readText(childResult));
            }
        } catch (error) { child = { ok: false, error: String(error) }; }
        if (child) {
            writeResult({
                ok: true, api: 'engines.execScriptFile',
                executionId: execution.id,
                child,
                note: '子引擎为独立 Node 进程/引擎，arguments 以键值对注入子引擎 execArgv（如 from/n）'
            });
            try { files.remove(childPath); files.remove(childResult); } catch (ignored) { }
            return;
        }
        if (Date.now() > deadline) {
            writeResult({ ok: false, api: 'engines.execScriptFile', error: '等待子引擎结果超时（10s）', executionId: execution.id });
            return;
        }
        setTimeout(poll, 200);
    })();
} catch (error) {
    writeResult({ ok: false, api: 'engines.execScriptFile', error: String(error && error.stack || error) });
}
