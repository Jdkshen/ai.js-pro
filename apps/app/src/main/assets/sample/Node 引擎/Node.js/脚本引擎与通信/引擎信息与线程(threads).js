"nodejs";
// 引擎信息与线程(threads)
// engines.myEngine()/all() 读引擎信息；threads.start 在独立 Worker 堆计算并通过 join 取回结果。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/09_engines_threads.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

const engines = require('engines');
const threads = require('threads');
try {
    const me = engines.myEngine();
    const config = me.executionConfig || {};
    const engineInfo = {
        id: me.id,
        engine: me.engine,
        source: me.source,
        execArgv: me.execArgv,
        projectDir: config.projectDir || null,
        engineClass: me.getClass().getName()
    };
    const all = engines.all().map(function (engine) {
        return { id: engine.id, source: engine.source, engine: engine.engine };
    });

    // Node Worker 有独立堆：函数体必须自包含，参数需可 JSON 序列化。
    const thread = threads.start(function (n) {
        let sum = 0;
        for (let i = 0; i < n; i++) sum += i;
        return { n: n, sum: sum, threadId: require('worker_threads').threadId };
    }, 100);
    const joined = thread.join(5000);
    writeResult({
        ok: joined && !thread.error,
        api: 'engines + threads',
        engine: engineInfo,
        allEngines: all,
        thread: {
            joined,
            result: thread.result,
            error: thread.error,
            note: 'Worker 独立堆；仅参数/返回值可序列化，不能共享闭包'
        }
    });
} catch (error) {
    writeResult({ ok: false, api: 'engines + threads', error: String(error && error.stack || error) });
}
