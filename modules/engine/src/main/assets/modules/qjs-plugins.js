/**
 * QuickJS 侧的 `plugins` 模块：Rhino 有 modules/__plugins__.js，QuickJS 这边补上同语义的
 * `$plugins.load(packageName)`。
 *
 * 加载链路与宿主 Java 的 Plugins.java 一致：
 *   1. Java 侧 createPackageContext + 反射注册类 `loadDefault(宿主Context, 插件Context, runtime, scope)`，
 *      并把插件 `assets/scripts/` 拷到宿主 `cache/plugin-scripts/<包名>/`；
 *   2. 这里 `require` 入口 `index.js`，用插件对象调用它，**它的返回值就是 plugins.load() 的返回值**。
 *
 * 与 Rhino 的差异只有一处：Rhino 把 `plugin.unwrap()` 直接传给入口脚本，这里给的是官方文档
 * 描述的插件对象（unwrap/getVersion/getAssetsScriptDir/getMainScriptPath），入口脚本两种都能吃。
 */
(function (g) {
    'use strict';

    function host() {
        return g.Java.type('com.stardust.autojs.engine.QuickJsApiExtensions')
            .forEngine(Number(g.engines.myEngine().id));
    }

    function text(value) {
        return value === undefined || value === null ? '' : String(value);
    }

    function versionOf(handle, instance) {
        try {
            if (typeof handle.getVersion === 'function') return text(handle.getVersion());
        } catch (error) { /* 换下面的实例方法 */ }
        try {
            if (instance && typeof instance.getVersion === 'function') return text(instance.getVersion());
        } catch (error) { /* 没有版本号也能用 */ }
        return '';
    }

    /** 官方文档里的「插件对象」形状（入口脚本按它写，就能同时跑在官方 Pro 上）。 */
    function wrap(handle) {
        var instance = handle.unwrap();
        return {
            getMainScriptPath: function () { return text(handle.getMainScriptPath()); },
            getAssetsScriptDir: function () { return text(handle.getAssetsScriptDir()); },
            getVersion: function () { return versionOf(handle, instance); },
            unwrap: function () { return instance; }
        };
    }

    function plugins() {
    }

    /**
     * 加载并初始化一个插件。
     * @param packageName 插件 APK 的包名
     * @returns 插件入口脚本 `index.js` 导出函数的返回值
     */
    plugins.load = function (packageName) {
        var name = text(packageName);
        var handle;
        try {
            handle = host().loadPlugin(name);
        } catch (error) {
            // 官方 MLKit OCR 插件没装时，用宿主内置的离线 MLKit（与 Rhino 侧同一口径）。
            if (name === 'org.autojs.autojspro.plugin.mlkit.ocr' && String(error).indexOf('NameNotFound') >= 0) {
                return BuiltInMLKitOCR;
            }
            throw error;
        }
        var mainScriptPath = text(handle.getMainScriptPath());
        if (mainScriptPath === '') {
            throw new Error('插件没有入口脚本（assets/scripts/index.js）：' + name);
        }
        var index = g.require(mainScriptPath);
        return index(wrap(handle));
    };

    // ---- 内置 MLKit OCR：官方插件缺位时的等价实现（Rhino 侧 __plugins__.js 的同款兜底）----
    function BuiltInMLKitOCR() {
        var session = g.$ocr.create({ backend: 'mlkit' });
        this.detect = function (image, options) { return session.detect(image, options); };
        this.release = function () { return session.release(); };
        this.isReleased = function () { return session.isReleased(); };
    }

    g.$plugins = plugins;
    g.plugins = plugins;
    if (g.__aiModernModules) {
        // require('plugins') 也能拿到（与 init.js 给 Rhino 挂的模块名保持一致）
        g.__aiModernModules.plugins = plugins;
    }
})(typeof globalThis !== 'undefined' ? globalThis : this);
