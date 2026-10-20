"nodejs";
// 动态加载Dex与宿主运行时(host.runtime)
// 演示 Pro 兼容 host.runtime / host.java.Class.forName / Packages / host.context；无 dex 时只打印用法。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/11_host_runtime.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

try {
    const out = { ok: true, api: 'host.runtime' };

    try {
        const loader = host.runtime.getAndroidClassLoader();
        out.classLoader = loader && loader.getClass ? loader.getClass().getName() : String(loader);
    } catch (error) { out.classLoaderError = String(error && error.message || error); }

    try {
        const buildClass = host.java.lang.Class.forName('android.os.Build');
        out.forName = { ok: true, name: buildClass && buildClass.getName ? buildClass.getName() : String(buildClass) };
    } catch (error) { out.forName = { ok: false, error: String(error && error.message || error) }; }

    try {
        // Pro 的 Packages 链可访问顶层类；嵌套类（Build$VERSION）要点号取不到，必须用 $ 写法。
        var pkgChain = (typeof Packages !== 'undefined') ? Packages : host.Packages;
        var buildClass = pkgChain.android.os.Build;
        out.packagesBuildClass = buildClass && buildClass.getName ? buildClass.getName() : String(buildClass);
        var versionClass = host.java.lang.Class.forName('android.os.Build$VERSION');
        out.packagesBuildVersionClass = versionClass && versionClass.getName ? versionClass.getName() : String(versionClass);
        out.packagesNote = 'Packages 链可访问顶层类；嵌套类 Build$VERSION 需用 $ 写法，不能点号取 VERSION';
    } catch (error) { out.packagesError = String(error && error.message || error); }

    try { out.contextPackage = host.context.getPackageName(); }
    catch (error) { out.contextPackageError = String(error && error.message || error); }

    // loadDex 可选：/sdcard/脚本/_probe 下放 dex 才真正加载；没有就只打印用法，保证示例可独立结束。
    const dexDir = '/sdcard/脚本/_probe';
    let dexFiles = [];
    try { if (files.isDir(dexDir)) { dexFiles = files.list(dexDir).filter(function (p) { return /\.dex$/i.test(String(p)); }); } } catch (ignored) { }
    if (dexFiles.length > 0) {
        try { out.loadDex = { path: dexFiles[0], ok: host.runtime.loadDex(dexFiles[0]) }; }
        catch (error) { out.loadDex = { path: dexFiles[0], ok: false, error: String(error && error.message || error) }; }
    } else {
        out.loadDex = { skipped: true, usage: '把自定义 dex 放到 ' + dexDir + '/ 后重跑本示例，会调用 host.runtime.loadDex(path)' };
    }

    writeResult(out);
} catch (error) {
    writeResult({ ok: false, api: 'host.runtime', error: String(error && error.stack || error) });
}
