"rhino";
(function () {
    var name = '__aijs_child_contract__' + Date.now();
    var store = storages.create(name);
    store.clear();
    var source = 'var s=storages.create(' + JSON.stringify(name) + ');'
        + 'var a=engines.myEngine().execArgv;'
        + 's.put("args",{zero:a.zero,disabled:a.disabled,text:String(a.text),empty:a.empty});'
        + 's.put("count",s.get("count",0)+1);s.put("cwd",String(files.cwd()));';
    engines.execScript('公共子脚本', source, {
        delay: 30, interval: 60, loopTimes: 2, path: [String(files.cwd())],
        arguments: {zero: 0, disabled: false, text: '中文', empty: null}
    });
    var started = Date.now();
    var timer = setInterval(function () {
        var count = store.get('count', 0);
        if (count < 2 && Date.now() - started < 8000) return;
        clearInterval(timer);
        var args = store.get('args', {});
        var checks = [
            {name: 'engines.loopTimes', ok: count === 2},
            {name: 'engines.arguments', ok: args.zero === 0 && args.disabled === false && args.text === '中文' && args.empty === null},
            {name: 'engines.path', ok: store.get('cwd') === String(files.cwd())}
        ];
        var failed = checks.filter(function (row) { return !row.ok; });
        console.log('COMMON_CHILD_RESULT=' + JSON.stringify({checks: checks, passed: checks.length - failed.length, failed: failed, args: args, count: count}));
        store.clear();
    }, 100);
})();
