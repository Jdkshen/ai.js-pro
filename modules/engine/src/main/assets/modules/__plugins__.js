module.exports = function (runtime, scope) {
    function plugins(){
    }

    // 第三方 OCR 插件（脚本硬编码包名，装不上就整脚本跑不起来）→ 缺插件时回退到内置 OCR。
    // 只对下面这些**已知 OCR 插件包**生效；其它插件仍然抛出真实错误，不假装成功。
    var OCR_PLUGIN_INSTANCE = {
        'com.hraps.ocr': true,
        'com.hraps.ocr32': true
    };
    var OCR_PLUGIN_CONSTRUCTOR = {
        'com.tomato.ocr': true
    };
    var warnedPackages = {};

    function missingPackage(error) {
        var text = String(error);
        return text.indexOf('NameNotFoundException') >= 0 || text.indexOf('not found') >= 0;
    }

    function warnOnce(packageName) {
        if (warnedPackages[packageName]) return;
        warnedPackages[packageName] = true;
        try {
            if (scope && scope.console && typeof scope.console.warn === 'function') {
                scope.console.warn('插件 ' + packageName + ' 未安装：已使用内置 OCR 兼容实现' +
                    '（结果结构与对应插件一致，__aijsBuiltInShim=true）。需要真实插件时请安装该插件 APK 后重跑。');
            }
        } catch (ignore) { /* 没有 console 也要保持静默，不影响脚本 */ }
    }

    /** 内置 OCR 后端优先级：ppocr 是随包离线模型（脚本进程里实测可用），mlkit 需要 MlKitContext。 */
    var OCR_BACKENDS = ['ppocr', 'mlkit'];

    /** 依次尝试后端，返回第一个能建起来的会话；全失败才抛最后一个错误。 */
    function createOcrSession(preferred) {
        var order = OCR_BACKENDS.filter(function(backend) { return backend !== preferred; });
        if (preferred) order.unshift(preferred);
        var lastError = null;
        for (var i = 0; i < order.length; i++) {
            try {
                return { backend: order[i], session: scope.$ocr.create({ backend: order[i] }) };
            } catch (error) {
                lastError = error;
            }
        }
        throw lastError || new Error('无法创建内置 OCR 会话（ppocr/mlkit 都失败）');
    }

    /** 内置 OCR 会话包成「插件实例」（浩然 OCR 的 ocr.detect(bitmap, mode) / end()）。 */
    function builtInOcrInstance(packageName) {
        var created = createOcrSession('ppocr');
        var session = created.session;
        var detect = function(image, mode) { return session.detect(image, mode); };
        return {
            __aijsBuiltInShim: true,
            backend: created.backend,
            packageName: packageName,
            detect: detect,
            ocrBitmap: detect,
            end: function() { return session.release(); },
            release: function() { return session.release(); },
            isReleased: function() { return session.isReleased(); }
        };
    }

    /** tomato 的用法是 `new ($plugins.load(...))()`，所以这里要回退成一个构造函数。 */
    function builtInOcrConstructor(packageName) {
        return function() { return builtInOcrInstance(packageName); };
    }

    plugins.load = function(packageName){
        var plugin;
        try {
            plugin = runtime.plugins.load(packageName);
        } catch (error) {
            var name = String(packageName);
            // The official legacy ML Kit plugin can use our bundled offline model when absent.
            if (name === 'org.autojs.autojspro.plugin.mlkit.ocr' && missingPackage(error)) {
                return BuiltInMLKitOCR;
            }
            if (missingPackage(error)) {
                if (OCR_PLUGIN_INSTANCE[name]) {
                    warnOnce(name);
                    return builtInOcrInstance(name);
                }
                if (OCR_PLUGIN_CONSTRUCTOR[name]) {
                    warnOnce(name);
                    return builtInOcrConstructor(name);
                }
            }
            throw error;
        }
        var index = require(plugin.mainScriptPath);
        return index(wrapPlugin(plugin));
    }

    /**
     * 插件对象：官方文档里给入口脚本的就是这一层（unwrap/getVersion/getAssetsScriptDir/
     * getMainScriptPath），所以按文档写的插件在本仓库也能直接跑。
     * 这里传过的是宿主 Plugin 包装对象；原生/胶水实例仍然只从 unwrap() 拿。
     */
    function wrapPlugin(plugin){
        return {
            mainScriptPath: plugin.mainScriptPath,
            getMainScriptPath: function(){ return plugin.mainScriptPath; },
            getAssetsScriptDir: function(){ return plugin.getAssetsScriptDir(); },
            getVersion: function(){
                try {
                    if (typeof plugin.getVersion === 'function') return plugin.getVersion();
                } catch (ignore) { /* 宿主包装对象没有版本号 */ }
                try {
                    var instance = plugin.unwrap();
                    if (instance && typeof instance.getVersion === 'function') return instance.getVersion();
                } catch (ignore2) { /* 插件自己的 SDK 也没报版本号 */ }
                return '';
            },
            unwrap: function(){ return plugin.unwrap(); }
        };
    }

    function BuiltInMLKitOCR() {
        // 首选 mlkit（官方插件语义）；当前进程建不起来时退到随包的 ppocr，避免脚本直接失败。
        var session = createOcrSession('mlkit').session;
        this.detect = function(image, options) { return session.detect(image, options); };
        this.release = function() { return session.release(); };
        this.isReleased = function() { return session.isReleased(); };
    }

    return plugins;
}
