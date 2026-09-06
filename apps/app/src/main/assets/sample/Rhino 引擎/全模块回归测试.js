// Rhino 引擎（默认引擎）全模块回归测试
// 普通 .js 默认走 Rhino 引擎；无需 // @engine quickjs
// 结果写入 /sdcard/脚本/zz_regress_rhino_result.json

var out = {};
function t(name, fn) {
    try { out[name] = String(fn()); }
    catch (e) { out[name] = 'ERR:' + e; }
}
t('files', function () { return typeof files; });
t('filesRead', function () { return files.read(files.cwd() + '/zz_regress_rhino.js').length > 0; });
t('device', function () { return typeof device; });
t('app', function () { return typeof app; });
t('appName', function () { return app.getAppName('com.jdkshen.aijspro') || ''; });
t('appInfo', function () {
    var info = JSON.parse(app.getAppInfo('com.jdkshen.aijspro'));
    return info.label + '/' + info.versionName;
});
t('events', function () { return typeof events; });
t('eventsEmit', function () {
    var r = null;
    events.once('zzr', function (v) { r = v; });
    events.emit('zzr', 7);
    return r;
});
t('media', function () { var b = media.getVolume(); media.setVolume(b); return 'ok'; });
t('sensors', function () { return typeof sensors; });
t('sqlite', function () { return typeof sqlite; });
t('sqliteOpen', function () {
    var db = sqlite.open('zz_regress');
    db.exec('CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY, v TEXT)');
    db.exec("INSERT OR REPLACE INTO t (id, v) VALUES (1, 'hello')");
    var r = db.exec("SELECT v FROM t WHERE id = 1");
    var got = '';
    if (r && r.rows && r.rows.length > 0) {
        try { got = r.rows.item(0).get('v'); } catch (e3) { got = 'ERR:' + e3; }
    }
    return got;
});
t('ui', function () { return typeof $ui; });
t('threads', function () { return typeof threads; });
t('images', function () { return typeof images; });
t('floaty', function () { return typeof floaty; });
t('dialogs', function () { return typeof dialogs; });
t('shell', function () { return typeof shell; });
t('toast', function () { return typeof toast; });
t('sleep', function () { var s = Date.now(); sleep(30); return Date.now() - s >= 25; });
t('require', function () { return typeof require; });
t('runtime', function () { return typeof runtime; });
t('engines', function () { return typeof engines; });

var pass = 0, fail = 0;
for (var key in out) {
    var value = out[key];
    if (typeof value === 'string' && value.indexOf('ERR:') === 0) { fail++; console.error('FAIL ' + key + ': ' + value); }
    else { pass++; console.log('PASS ' + key + ' = ' + value); }
}
console.log('Rhino 回归完成: ' + pass + ' pass, ' + fail + ' fail');
files.write('/sdcard/脚本/zz_regress_rhino_result.json', JSON.stringify(out));
