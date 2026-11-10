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

function pluginOcrCreateOrNull(options) {
    try {
        if (typeof $plugins === 'undefined' || !$plugins || typeof $plugins.load !== 'function') {
            return null;
        }
        var plugin = $plugins.load(OCR_PLUGIN_PACKAGE);
        if (!plugin) {
            return null;
        }
        // 就绪判定（能查才查；查不了就交给插件自己报错，不在这里猜）。
        try {
            if (typeof context !== 'undefined' && context && Packages && Packages.com) {
                var Loader = Packages.com.stardust.autojs.plugin.PluginNativeLoader;
                var info = context.getPackageManager().getPackageInfo(OCR_PLUGIN_PACKAGE, 0);
                // Ready? If not, stage once lazily (task-142); still not ready => fall back to the builtin chain.
                if (!ensurePluginLibsReady(Loader, info.versionCode)) {
                    return null;
                }
            }
        } catch (ignored) {
        }
        var create = (typeof plugin.create === 'function') ? plugin.create
                : (plugin.ocr && typeof plugin.ocr.create === 'function' ? plugin.ocr.create : null);
        if (create === null) {
            return null;
        }
        return create.call(plugin.ocr && plugin.ocr.create === create ? plugin.ocr : plugin, options);
    } catch (error) {
        // 插件报错不吞：写一条日志后回落既有链路（点名的错由既有链路给）。
        try { console.warn('OCR 插件不可用，回落到内置链路：' + error); } catch (ignored) {
        }
        return null;
    }
}

// task-143 (approved): REAL loadability probe - pure file existence check (no System.load, no side effects).
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

// true = file present, false = definitely missing, null = cannot tell (keep legacy behaviour).
function nativeLibPresent(fileName) {
    try {
        var dir = nativeLibraryDirOrNull();
        if (dir === null) {
            return null;
        }
        return new java.io.File(dir, fileName).isFile();
    } catch (ignored) {
        return null;
    }
}

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
            return pluginSession;
        }
        var backend = String(options.backend == undefined ? 'mlkit' : options.backend).toLowerCase();
        // task-105：ppocr 未随包时**点名**报错（不是 UnsatisfiedLinkError / 静默 undefined）。
        if ((backend === 'ppocr' || backend === 'paddle' || backend === 'paddleocr')
                && (ppocrIsAvailable() === false || nativeLibPresent('libppocr.so') === false)) {
            throw new Error(OCR_NOT_PACKAGED);
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
                mlkitAvailable = Ocr.ensureMlKitInitialized() === true
                        && nativeLibPresent('libmlkit_google_ocr_pipeline.so') !== false;
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
        return {
            backend: backend,
            requestedBackend: String(options.backend == undefined ? 'mlkit' : options.backend).toLowerCase(),
            degraded: degradedFrom !== null,
            degradedFrom: degradedFrom,
            degradeReason: degradeReason,
            detect: function (image, detectOptions) {
                detectOptions = detectOptions || {};
                var max = detectOptions.max == undefined ? 1000 : parseInt(detectOptions.max);
                var detectRotation = detectOptions.detectRotation === true;
                // Java 侧返回 OCRResult[]。**必须逐项转成纯 JS 对象**：OCRResult 上有 android.graphics.Rect
                // 和 javaObject（原始 ML Kit 对象），直接塞进数组时 `console.log(result)`（官方示例就是这么写的）
                // 会去序列化 Java 对象图，一路走到宿主 Activity/RecyclerView，报
                // `class androidx.recyclerview.widget.RecyclerView declares multiple JSON fields named
                // mAccessibilityDelegate`（__json2__.js#234）——这就是内置谷歌 OCR 示例的第二个报错。
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
    };

    return ocr;
};
