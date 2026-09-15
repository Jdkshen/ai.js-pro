// PDF §2.22 OCR（第 182~185 页）：$ocr.create([options]) / OCR.detect(image[, options]) / OCR.release()
//
// ⚠️ 与 Pro 的实现差异（记入对照文档 §9）：Pro 用插件商店的 PaddleOCR，
// 这里用 ML Kit 中文文字识别（模型内置、离线）。因此文档里的 models / labelsFile /
// cpuPowerMode / 线程数等选项是 PaddleOCR 专用参数：接口上接受（不报错），但不改变识别引擎。
module.exports = function (runtime, global) {
    var Ocr = com.stardust.autojs.runtime.api.Ocr;
    var ocr = {};

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
        var session = new Ocr(runtime);
        // Java 对象上加不了属性（Rhino 限制），所以用 JS 包装暴露方法
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
