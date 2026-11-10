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
    //
    // ML Kit 的 InitProvider 只在主进程跑；:script 进程里 MlKitContext 可能起不来。
    // 旧实现直接 $ocr.create({backend:'mlkit'})，create 成功但 detect 才抛
    // MlKitContext has not been initialized。这里先问一次 Java 侧（幂等初始化），
    // 不可用就提前退到随包的 ppocr，并在返回对象里如实标注降级来源。
    function mlKitUnavailableReason() {
        try {
            if (!g.Java || typeof g.Java.type !== 'function') {
                return 'QuickJS 缺少 Java.type，无法检查 MlKitContext';
            }
            var Ocr = g.Java.type('com.stardust.autojs.runtime.api.Ocr');
            if (!Ocr || typeof Ocr.ensureMlKitInitialized !== 'function') {
                return 'Ocr.ensureMlKitInitialized 不可用（当前引擎版本过旧）';
            }
            if (!Ocr.ensureMlKitInitialized()) {
                return 'MlKitContext has not been initialized（本进程无法初始化 ML Kit）';
            }
            return null;
        } catch (error) {
            return '检查 ML Kit 初始化失败：' + text(error);
        }
    }

    // task-105：与 Java OcrAvailability.NOT_PACKAGED_MESSAGE 逐字一致（三引擎同源）。
    var OCR_NOT_PACKAGED = 'OCR 能力未随包（optimization.removeOcr=true）：libppocr.so / libmlkit* 不在包内；如需 OCR 请把该开关关掉并重新打包。';

    function builtInOcrUnavailable(reason, ppocrError) {
        var failure = new Error(OCR_NOT_PACKAGED + '（内置 OCR 不可用：mlkit 原因=' + reason
            + '；ppocr 创建失败=' + text(ppocrError) + '）');
        failure.degraded = true;
        failure.degradedFrom = 'mlkit';
        failure.degradeReason = reason;
        return failure;
    }

    function createBuiltInOcrSession() {
        var reason = mlKitUnavailableReason();
        if (reason === null) {
            try {
                var mlkitSession = g.$ocr.create({ backend: 'mlkit' });
                // $ocr.create 的 guard 可能在 mlkit 创建失败时已提前退到 ppocr；
                // 以会话上的真实字段为准，别把已经降级的会话误报成 mlkit。
                return {
                    backend: mlkitSession && mlkitSession.backend ? mlkitSession.backend : 'mlkit',
                    session: mlkitSession,
                    degraded: !!(mlkitSession && mlkitSession.degraded === true),
                    degradedFrom: mlkitSession && mlkitSession.degradedFrom
                        ? mlkitSession.degradedFrom : null,
                    degradeReason: mlkitSession && mlkitSession.degradeReason
                        ? mlkitSession.degradeReason : null
                };
            } catch (error) {
                reason = '创建 mlkit 会话失败：' + text(error);
            }
        }
        try {
            return {
                backend: 'ppocr',
                session: g.$ocr.create({ backend: 'ppocr' }),
                degraded: true,
                degradedFrom: 'mlkit',
                degradeReason: reason
            };
        } catch (error) {
            throw builtInOcrUnavailable(reason, error);
        }
    }

    function BuiltInMLKitOCR() {
        var created = createBuiltInOcrSession();
        var session = created.session;
        // 与 Rhino 侧 __ocr__.js 的同名字段保持可观察：脚本不必 detect 就可以判断后端。
        this.__aijsBuiltInShim = true;
        this.requestedBackend = 'mlkit';
        this.backend = created.backend;
        this.degraded = created.degraded;
        this.degradedFrom = created.degradedFrom;
        this.degradeReason = created.degradeReason;
        this.detect = function (image, options) { return session.detect(image, options); };
        this.release = function () { return session.release(); };
        this.isReleased = function () { return session.isReleased(); };
    }

    // ---- 直接 $ocr.create 的同源保护 ----
    //
    // qjs-plugins 在 QuickJS 启动时加载（早于用户脚本）。除了上面的插件兜底，
    // 直接调用 $ocr.create({backend:'mlkit'}) 也有同样的「create 成功、detect 才炸」风险：
    // C++ 侧默认 backend=mlkit，Java Ocr 构造器虽然会 ensure，但返回值没有传回 JS。
    // 因此这里只做一层很薄的包装：先 ensure；不可用就提前改走 ppocr，并给会话加可观察字段。
    function requestedOcrBackend(options) {
        if (options == null || options.backend == null || options.backend === '') {
            return 'mlkit';
        }
        var raw = String(options.backend).toLowerCase().replace(/[\s_-]+/g, '');
        if (raw === '') {
            return 'mlkit';
        }
        if (raw === 'ppocr' || raw === 'paddle' || raw === 'paddleocr') {
            return 'ppocr';
        }
        if (raw === 'mlkit') {
            return 'mlkit';
        }
        return null;   // 未知 backend 交给原实现，不在这里改变它的报错语义
    }

    function optionsWithBackend(options, backend) {
        var copy = {};
        if (options != null && typeof options === 'object') {
            for (var key in options) {
                if (Object.prototype.hasOwnProperty.call(options, key) && key !== 'backend') {
                    copy[key] = options[key];
                }
            }
        }
        copy.backend = backend;
        return copy;
    }

    function annotateOcrSession(session, requested, backend, degradeReason) {
        if (!session) return session;
        try {
            session.requestedBackend = requested;
            session.backend = backend;
            session.degraded = degradeReason !== null;
            session.degradedFrom = degradeReason !== null ? requested : null;
            session.degradeReason = degradeReason;
        } catch (ignore) { /* 个别原生会话对象不可扩展时，至少保持可用 */ }
        return session;
    }

    function installOcrCreateGuard() {
        if (!g.$ocr || typeof g.$ocr.create !== 'function') return;
        var originalCreate = g.$ocr.create;
        g.$ocr.create = function (options) {
            var requested = requestedOcrBackend(options);
            if (requested === null) {
                return originalCreate.call(g.$ocr, options);
            }
            if (requested === 'ppocr') {
                return annotateOcrSession(
                    originalCreate.call(g.$ocr, optionsWithBackend(options, 'ppocr')),
                    requested, 'ppocr', null);
            }
            var reason = mlKitUnavailableReason();
            if (reason === null) {
                try {
                    return annotateOcrSession(originalCreate.call(g.$ocr, options), requested, 'mlkit', null);
                } catch (error) {
                    reason = '创建 mlkit 会话失败：' + text(error);
                }
            }
            try {
                return annotateOcrSession(
                    originalCreate.call(g.$ocr, optionsWithBackend(options, 'ppocr')),
                    requested, 'ppocr', reason);
            } catch (error) {
                throw builtInOcrUnavailable(reason, error);
            }
        };
    }

    installOcrCreateGuard();

    g.$plugins = plugins;
    g.plugins = plugins;
    if (g.__aiModernModules) {
        // require('plugins') 也能拿到（与 init.js 给 Rhino 挂的模块名保持一致）
        g.__aiModernModules.plugins = plugins;
    }
})(typeof globalThis !== 'undefined' ? globalThis : this);
