// PDF §2.22 OCR（第 182~185 页）：$ocr.create([options]) / OCR.detect(image[, options]) / OCR.release()
//
// 两个后端（结果结构完全一致，脚本可无缝切换）：
//   - 默认：ML Kit 中文文字识别（模型内置、离线）
//   - {backend:'ppocr'}：ncnn 版 PP-OCRv6（tiny，更准；只随包 arm64-v8a）
// 因此文档里的 models / labelsFile / cpuPowerMode / 线程数等 PaddleOCR 参数，在 ppocr 后端上
// **真的生效**（ML Kit 后端接受但不改变引擎）。
module.exports = function (runtime, global) {
    var Ocr = com.stardust.autojs.runtime.api.Ocr;
    var PPOcr = com.stardust.autojs.runtime.api.PPOcr;
    var ocr = {};

    // 探针用：当前进程内未释放的 OCR 会话数（脚本忘 release() 时应该在下个脚本里看到 0）
    ocr.liveCount = function () {
        return Ocr.liveCount() + PPOcr.liveCount();
    };

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

    // 文档：根据给定选项创建 OCR 对象（每次调用都是一个新对象）
    ocr.create = function (options) {
        options = options || {};
        var backend = String(options.backend == undefined ? 'mlkit' : options.backend).toLowerCase();
        var session;
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
            detect: function (image, detectOptions) {
                detectOptions = detectOptions || {};
                var max = detectOptions.max == undefined ? 1000 : parseInt(detectOptions.max);
                var detectRotation = detectOptions.detectRotation === true;
                // Java 侧返回 OCRResult[]，这里转成真正的 JS 数组，
                // 这样文档示例里的 forEach/filter/find 才能直接用
                var javaResults = session.detect(image, max, detectRotation, toIntArray(detectOptions.region));
                var results = [];
                for (var i = 0; i < javaResults.length; i++) {
                    results.push(javaResults[i]);
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
