// PDF §2.22 OCR（第 182~185 页）：$ocr.create([options]) / OCR.detect(image[, options]) / OCR.release()
//
// 两个后端（结果结构完全一致，脚本可无缝切换）：
//   - 默认：ML Kit 中文文字识别（模型内置、离线）
//   - {backend:'ppocr'}：ncnn 版 PP-OCRv6（tiny，更准；只随包 arm64-v8a）
// 因此文档里的 models / labelsFile / cpuPowerMode / 线程数等 PaddleOCR 参数，在 ppocr 后端上
// **真的生效**（ML Kit 后端接受但不改变引擎）。
module.exports = function (runtime, global) {
    var Ocr = com.stardust.autojs.runtime.api.Ocr;
    // task-141：OCR 插件（org.autojs.autojspro.plugin.ocr）优先 —— 装了且就绪就用插件实现；
// 否则返回 null，调用方继续走**既有链路**（无插件时那条点名错误，不新增第二份文案）。
var OCR_PLUGIN_PACKAGE = 'org.autojs.autojspro.plugin.ocr';

// task-142 (approved): lazy plugin-libs staging on first $ocr use (no plugin-page callback needed).
var ocrPluginSyncAttempted = false;

function ensurePluginLibsReady(Loader, versionCode) {
    try {
        if (!Loader || typeof Loader.isReady !== 'function') {
            return true;
        }
        if (Loader.isReady(context, OCR_PLUGIN_PACKAGE, versionCode)) {
            return true;
        }
        if (ocrPluginSyncAttempted) {
            return false;
        }
        ocrPluginSyncAttempted = true;
        if (typeof Loader.syncPluginLibs !== 'function') {
            return false;
        }
        Loader.syncPluginLibs(context, OCR_PLUGIN_PACKAGE, versionCode);
        var ready = Loader.isReady(context, OCR_PLUGIN_PACKAGE, versionCode) === true;
        try {
            console.info('[OCR] plugin libs lazy staging: ready=' + ready + ' pkg=' + OCR_PLUGIN_PACKAGE + ' v=' + versionCode);
        } catch (ignore) {
        }
        return ready;
    } catch (error) {
        try { console.warn('[OCR] plugin libs staging failed (fallback to builtin chain): ' + error); } catch (ignore) {
        }
        return false;
    }
}


// task-143/145: REAL loadability probe - pure file existence check (no System.load, no side effects).
function nativeLibraryDirOrNull() {
    try {
        if (typeof context !== 'undefined' && context && typeof context.getApplicationInfo === 'function') {
            var dir = context.getApplicationInfo().nativeLibraryDir;
            return dir ? new java.io.File(dir) : null;
        }
    } catch (ignored) {
    }
    return null;
}

// true = present, false = definitely missing, null = cannot tell (keep legacy behaviour).
function nativeLibPresent(fileName) {
    try {
        var dir = nativeLibraryDirOrNull();
        if (dir !== null && new java.io.File(dir, fileName).isFile()) {
            return true;
        }
        try {
            if (typeof context !== 'undefined' && context && Packages && Packages.com) {
                var pkgS = OCR_PLUGIN_PACKAGE;
                var infoS = context.getPackageManager().getPackageInfo(pkgS, 0);
                var staged = new java.io.File(new java.io.File(new java.io.File(context.getFilesDir(), 'plugins'), pkgS),
                        String(infoS.versionCode));
                if (new java.io.File(new java.io.File(staged, 'lib'), fileName).isFile()) {
                    return true;
                }
            }
        } catch (ignoredStaged) {
        }
        return dir === null ? null : false;
    } catch (ignored) {
        return null;
    }
}

function pluginOcrCreateOrNull(options) {
    var pkg = OCR_PLUGIN_PACKAGE;
    var plugin = null;
    try {
        if (typeof $plugins === 'undefined' || !$plugins || typeof $plugins.load !== 'function') {
            console.info('[OCR] plugin load skipped: $plugins 不可用');
            return null;
        }
        try {
            plugin = $plugins.load(pkg);
        } catch (loadError) {
            console.warn('[OCR] plugin loaded=false (load threw): ' + loadError);
            return null;
        }
        console.info('[OCR] plugin loaded=' + (plugin !== null && plugin !== undefined) + ' pkg=' + pkg);
        if (plugin === null || plugin === undefined) {
            return null;
        }
        // 就绪判定：能查就查 + 惰性落盘一次（task-142）；查不了交给插件自己报错，不在这里猜。
        try {
            if (typeof context !== 'undefined' && context && Packages && Packages.com) {
                var Loader = Packages.com.stardust.autojs.plugin.PluginNativeLoader;
                var info = context.getPackageManager().getPackageInfo(pkg, 0);
                if (!ensurePluginLibsReady(Loader, info.versionCode)) {
                    console.warn('[OCR] plugin libs not ready after lazy staging pkg=' + pkg + ' v=' + info.versionCode);
                    return null;
                }
            }
        } catch (readyError) {
            console.warn('[OCR] plugin readiness check failed (continue anyway): ' + readyError);
        }
        // task-146 (Lead 拍板): MAIN PATH = host builds the session directly, no plugin create/unwrap needed.
        if (typeof context !== 'undefined' && context && Packages && Packages.com) {
            try {
                var LoaderD = Packages.com.stardust.autojs.plugin.PluginNativeLoader;
                var infoD = context.getPackageManager().getPackageInfo(pkg, 0);
                var libDirD = (typeof LoaderD.libsDirOrNull === 'function')
                        ? LoaderD.libsDirOrNull(context, pkg, infoD.versionCode) : null;
                var modelDirD = (typeof LoaderD.modelsDirOrNull === 'function')
                        ? LoaderD.modelsDirOrNull(context, pkg, infoD.versionCode) : null;
                var backendD = 'ppocr';
                try {
                    if (modelDirD === null || modelDirD === undefined) {
                        console.warn('[OCR] plugin staged no models; builtin model dir will be used if any');
                    }
                } catch (ignoredModelLog) {
                }
                var sessionD = LoaderD.createOcrSession(runtime, context, pkg, infoD.versionCode,
                        libDirD === null ? null : String(libDirD),
                        modelDirD === null ? null : String(modelDirD), backendD);
                if (sessionD && typeof sessionD.detect === 'function') {
                    console.info('[OCR] host-built session (direct factory) backend=' + backendD
                            + ' libDir=' + libDirD + ' modelDir=' + modelDirD);
                    // task-147: same wrapper as the builtin path => scripts see one shape (detect(image[, options])).
                    return wrapSession(sessionD, backendD, backendD, null, null);
                }
                console.warn('[OCR] direct factory returned no detect(); fall through to plugin create');
            } catch (directError) {
                console.warn('[OCR] direct factory failed (try plugin create): ' + directError);
            }
        }
        // bug 1 fix: runtime.plugins.load() returns the engine Plugin WRAPPER; the plugin's create()
        // lives on the unwrapped instance (Plugin.unwrap(), Plugin.java:74). Java objects expose no keys,
        // so we TRY-CALL instead of probing existence.
        var candidates = [];
        var instance = plugin;
        try {
            if (plugin && typeof plugin.unwrap === 'function') {
                var unwrapped = plugin.unwrap();
                if (unwrapped) {
                    instance = unwrapped;
                }
            }
        } catch (unwrapError) {
            console.warn('[OCR] plugin.unwrap() threw (use wrapper): ' + unwrapError);
        }
        candidates.push({ owner: instance, name: 'instance.create' });
        try {
            if (instance && instance.ocr && typeof instance.ocr === 'function') {
                var ocrHolder = instance.ocr();
                if (ocrHolder) {
                    candidates.push({ owner: ocrHolder, name: 'instance.ocr().create' });
                }
            } else if (instance && instance.ocr && instance.ocr.create) {
                candidates.push({ owner: instance.ocr, name: 'instance.ocr.create' });
            }
        } catch (ocrAccessError) {
            console.warn('[OCR] plugin.ocr() threw: ' + ocrAccessError);
        }
        if (instance !== plugin) {
            candidates.push({ owner: plugin, name: 'wrapper.create' });
        }
        var session = null;
        var createErrors = [];
        for (var ci = 0; ci < candidates.length; ci++) {
            var candidate = candidates[ci];
            var fn = null;
            try {
                fn = candidate.owner ? candidate.owner.create : null;
            } catch (accessError) {
                createErrors.push(candidate.name + ': ' + accessError);
                continue;
            }
            if (typeof fn !== 'function') {
                createErrors.push(candidate.name + ': no create()');
                continue;
            }
            try {
                session = fn.call(candidate.owner, options);
                console.info('[OCR] plugin create via ' + candidate.name);
                break;
            } catch (createError) {
                createErrors.push(candidate.name + ' threw: ' + createError);
            }
        }
        if (session === null || session === undefined) {
            console.warn('[OCR] plugin create unavailable: ' + createErrors.join(' | '));
            return null;
        }
        // 方案 A'/B（裁决）：插件返回**描述符** ⇒ 调用宿主侧的会话工厂（唯一来源，复用既有实现）。
        if (session !== null && session !== undefined && typeof session.detect !== 'function') {
            var keys = (typeof session === 'object') ? Object.keys(session).join(',') : typeof session;
            if (typeof context === 'undefined' || !context || !Packages || !Packages.com) {
                console.warn('[OCR] plugin descriptor needs host context/Packages; keys=' + keys);
                return null;
            }
            var LoaderA = Packages.com.stardust.autojs.plugin.PluginNativeLoader;
            var infoA = context.getPackageManager().getPackageInfo(pkg, 0);
            var backendA = (session && session.backend) ? String(session.backend) : 'ppocr';
            try {
                var builtSession = LoaderA.createOcrSession(runtime, context, pkg, infoA.versionCode,
                        session ? session.libDir : null, session ? session.modelPath : null, backendA);
                if (builtSession && typeof builtSession.detect === 'function') {
                    console.info('[OCR] host-built session from plugin descriptor: backend=' + backendA
                            + ' libs=' + ((session && session.libs) ? session.libs.join('+') : '?')
                            + ' keys=' + keys);
                    // task-148: MUST go through the same wrapper - a raw Java session here is exactly what
                    // produced "Can't find method PPOcr.detect(ImageWrapper)" on device.
                    return wrapSession(builtSession, backendA, backendA, null, null);
                }
                console.warn('[OCR] createOcrSession returned no detect(); keys=' + keys);
                return null;
            } catch (factoryError) {
                // 不再静默：把完整 error 文本打出来，再回落到内置链路。
                console.warn('[OCR] createOcrSession threw (fallback to builtin chain): ' + factoryError);
                return null;
            }
        }
        console.info('[OCR] plugin session in use pkg=' + pkg + ' backend=' + (session.backend || 'plugin'));
        // task-148 (Lead 决定): wrap EVERY exit - even a session handed back by the plugin itself must be
        // indistinguishable to scripts (detect(image[, options]) + backend). Same wrapper, not a second impl.
        var pluginOwnBackend = (session && session.backend) ? String(session.backend) : 'plugin';
        return wrapSession(session, pluginOwnBackend, pluginOwnBackend, null, null);
    } catch (error) {
        try { console.warn('[OCR] plugin path failed (fallback to builtin chain): ' + error); } catch (ignored) {
        }
        return null;
    }
}

var pluginLibsLoaded = false;
var pluginDescriptor = null;
var PPOcr = com.stardust.autojs.runtime.api.PPOcr;
// task-105：与 Java OcrAvailability.NOT_PACKAGED_MESSAGE 逐字一致的共用文案（三引擎同源）。
var OCR_NOT_PACKAGED = 'OCR 能力未随包（optimization.removeOcr=true）：libppocr.so / libmlkit* 不在包内；如需 OCR 请把该开关关掉并重新打包。';
    var ocr = {};

    // 探针用：当前进程内未释放的 OCR 会话数（脚本忘 release() 时应该在下个脚本里看到 0）
    ocr.liveCount = function () {
        return Ocr.liveCount() + PPOcr.liveCount();
    };

    /**
     * OCRResult（Java）→ 纯 JS 对象。字段与 PDF 文档一致：text / confidence / bounds / rotation /
     * rotationConfidence；`bounds` 附 x/y/width/height 便于直接用。原始 javaObject 不放进数据字段
     * （会把 app 对象图带进 console.log/JSON），需要时用 raw() 取。
     */

    // task-147: ONE session wrapper used by BOTH the builtin backends and the plugin-provided session.
    // Builtin sessions and the plugin session must be indistinguishable to scripts (same detect(image[, options])).
    function wrapSession(session, backend, requestedBackend, degradedFrom, degradeReason) {
        try {
            console.info('[OCR] session shape: detect=' + typeof session.detect + '/'
                    + (session.detect ? session.detect.length : 'n/a') + ' release=' + typeof session.release
                    + ' backend=' + backend);
        } catch (shapeLogError) {
        }
        return {
            backend: backend,
            requestedBackend: requestedBackend,
            degraded: degradedFrom !== null && degradedFrom !== undefined,
            degradedFrom: degradedFrom === undefined ? null : degradedFrom,
            degradeReason: degradeReason === undefined ? null : degradeReason,
            detect: function (image, detectOptions) {
                detectOptions = detectOptions || {};
                var max = detectOptions.max == undefined ? 1000 : parseInt(detectOptions.max);
                var detectRotation = detectOptions.detectRotation === true;
                var javaResults = session.detect(image, max, detectRotation, toIntArray(detectOptions.region));
                var results = [];
                for (var i = 0; i < javaResults.length; i++) {
                    results.push(toPlainResult(javaResults[i]));
                }
                return results;
            },
            release: function () {
                session.release();
            },
            isReleased: function () {
                return session.isReleased();
            }
        };
    }

    function toPlainResult(javaResult) {
        var text = '';
        var confidence = 0;
        var rotation = 0;
        var rotationConfidence = 0;
        var bounds = null;
        try { text = javaResult.getText(); } catch (ignoreText) {}
        if (text == null) { text = ''; }
        try { confidence = Number(javaResult.getConfidence()); } catch (ignoreConfidence) {}
        try { rotation = Number(javaResult.getRotation()); } catch (ignoreRotation) {}
        try { rotationConfidence = Number(javaResult.getRotationConfidence()); } catch (ignoreRotationConfidence) {}
        try {
            var rect = javaResult.getBounds();
            if (rect != null) {
                bounds = {
                    left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
                    x: rect.left, y: rect.top, width: rect.width(), height: rect.height()
                };
            }
        } catch (ignoreBounds) {}
        return {
            text: String(text),
            confidence: confidence,
            bounds: bounds,
            rotation: rotation,
            rotationConfidence: rotationConfidence,
            /** 原始 Java 对象（方法而非字段：不会被 JSON/console.log 序列化）。 */
            raw: function () { return javaResult.getJavaObject(); }
        };
    }

    function toIntArray(array) {
        if (array == undefined || array == null) {
            return null;
        }
        if (typeof array.length != 'number') {
            throw new TypeError('region 必须是数组');
        }
        var out = [];
        for (var i = 0; i < array.length; i++) {
            out.push(parseInt(array[i]));
        }
        return out;
    }

    /**
     * task-74：当前 ABI 是否有 libppocr.so。
     * 返回 true/false；**查不到**（QuickJS/Node 等没有这个 Java 桥）返回 null，
     * 调用方据此保持旧行为（v7a 之外的引擎行为逐字不变）。
     */
    function ppocrIsAvailable() {
        try {
            var bridge = Packages.com.stardust.autojs.runtime.api.PPOcrBridge;
            if (bridge != null && typeof bridge.isAvailable === 'function') {
                return bridge.isAvailable() === true;
            }
        } catch (ignore) {
        }
        return null;
    }

    // 文档：根据给定选项创建 OCR 对象（每次调用都是一个新对象）
    ocr.create = function (options) {
        options = options || {};
        // task-141：插件优先（返回 null 则继续下面既有逻辑）。
        var pluginSession = pluginOcrCreateOrNull(options);
        if (pluginSession !== null && pluginSession !== undefined) {
            try {
                console.info('[OCR] pluginSession shape at escape: detect=' + typeof pluginSession.detect
                        + ' backend=' + pluginSession.backend);
            } catch (escapeLogError) {
            }
            return pluginSession;
        }
        var backend = String(options.backend == undefined ? 'mlkit' : options.backend).toLowerCase();
        // task-105：ppocr 未随包时**点名**报错（不是 UnsatisfiedLinkError / 静默 undefined）。
        // 插件已把库加载进本进程（方案 B）时，不再用「宿主 lib 目录里没有」判死。
        if (!pluginLibsLoaded && (backend === 'ppocr' || backend === 'paddle' || backend === 'paddleocr')
                && (ppocrIsAvailable() === false || nativeLibPresent('libppocr.so') === false)) {
            throw new Error(OCR_NOT_PACKAGED);
        }
        if (pluginLibsLoaded && pluginDescriptor && pluginDescriptor.backend
                && options.backend == undefined) {
            backend = String(pluginDescriptor.backend).toLowerCase();
        }
        if (pluginLibsLoaded && pluginDescriptor && pluginDescriptor.modelPath
                && (options.models == undefined || options.models === null)) {
            options.models = String(pluginDescriptor.modelPath);
        }
        var degradedFrom = null;
        var degradeReason = null;
        var session;
        // task-142：mlkit 可用性检查必须**安全**（库被 removeOcr 剔掉时 ensureMlKitInitialized 可能直接抛）。
        var mlkitAvailable = false;
        if (backend !== 'ppocr') {
            try {
                // ensureMlKitInitialized() only does idempotent init and never touches the .so (dev-1484),
                // so combine it with a REAL file-existence probe for the mlkit pipeline library.
                mlkitAvailable = (pluginLibsLoaded || Ocr.ensureMlKitInitialized() === true)
                        && (pluginLibsLoaded || nativeLibPresent('libmlkit_google_ocr_pipeline.so') !== false);
            } catch (mlkitError) {
                mlkitAvailable = false;
                try { console.warn('[OCR] ML Kit 不可用（检查即失败）：' + mlkitError); } catch (ignore) {}
            }
        } else {
            mlkitAvailable = true; // 非 mlkit 后端不查
        }
        if (backend !== 'ppocr' && !mlkitAvailable) {
            // ML Kit 在本进程起不来（拿不到 Application Context / 组件被裁剪）：
            // 以前 create() 会「成功」直到 detect() 才抛 MlKitContext has not been initialized，
            // 所以这里提前退到随包 ppocr，并如实标注降级来源。
            // task-74：**只有 ppocr 真的可用**（本 ABI 带了 libppocr.so）才允许退；v7a 不带 libppocr.so，
            // 谎报降级会在 detect 时炸成 native 错误 —— 直接给「两个后端都不可用」的清晰错误。
            // ppocrAvailable === null 表示「查不到」（QuickJS/Node 等没有这个 Java 桥）→ 保持旧行为。
            var ppocrAvailable = ppocrIsAvailable();
            if (ppocrAvailable === false) {
                // task-142：两个后端都不可用 ⇒ **出口必须是那条既有点名错误**（插件已优先尝试过、此处无插件）。
                // 后面括号里保留 v7a 场景的提示（v7a 天生没有 libppocr.so，不是 removeOcr）。
                var failure = new Error(OCR_NOT_PACKAGED
                    + '（本 ABI 也没有 libppocr.so：v7a 场景请用 backend:"mlkit" 或安装 OCR 插件）');
                failure.code = 'OCR_UNAVAILABLE';
                failure.reason = 'MlKitContext has not been initialized';
                failure.ppocrAvailable = false;
                failure.requestedBackend = String(options.backend == undefined ? 'mlkit' : options.backend).toLowerCase();
                throw failure;
            }
            degradedFrom = backend;
            degradeReason = 'MlKitContext has not been initialized（本进程无法初始化 ML Kit）';
            backend = 'ppocr';
            try { console.warn('[OCR] ML Kit 在本进程不可用，已退回随包 PP-OCRv6：' + degradeReason); } catch (ignore) {}
        }
        if (backend === 'ppocr') {
            // ppocr：这些参数真的会传给 ncnn 引擎（0/undefined = 用默认值）。
            // ⚠️ Pro 样例常把 cpuPowerMode 传成字符串（如 'LITE_POWER_NO_BIND'）、把 models 传成
            // 'slim' 这类档位名 —— 这里都当作「没给」，避免 NaN/把档位名当目录路径。
            var models = (options.models == undefined || options.models === null
                || String(options.models).charAt(0) !== '/') ? null : String(options.models);
            var cpuPower = options.cpuPowerMode == undefined ? 0 : parseInt(options.cpuPowerMode);
            if (isNaN(cpuPower)) { cpuPower = 0; }
            var threads = options.threads == undefined
                ? (options.parallelThreads == undefined ? 0 : parseInt(options.parallelThreads))
                : parseInt(options.threads);
            if (isNaN(threads)) { threads = 0; }
            var minScore = options.minScore == undefined ? 0 : Number(options.minScore);
            if (isNaN(minScore)) { minScore = 0; }
            var targetSize = options.targetSize == undefined ? 0 : parseInt(options.targetSize);
            if (isNaN(targetSize)) { targetSize = 0; }
            var useFp16 = options.useFp16 === true;
            var recMaxWidth = options.recMaxWidth == undefined ? 0 : parseInt(options.recMaxWidth);
            if (isNaN(recMaxWidth)) { recMaxWidth = 0; }
            session = new PPOcr(runtime, models, cpuPower, threads, minScore, targetSize, useFp16, recMaxWidth);
        } else {
            session = new Ocr(runtime);    // Java 对象上加不了属性（Rhino 限制），所以用 JS 包装暴露方法
        }
        return wrapSession(session, backend,
                String(options.backend == undefined ? 'mlkit' : options.backend).toLowerCase(),
                degradedFrom, degradeReason);
    };
    };

    return ocr;
};
