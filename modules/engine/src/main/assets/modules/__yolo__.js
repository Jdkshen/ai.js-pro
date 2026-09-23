module.exports = function (runtime, scope) {
    var javaYolo = runtime.getYolo();

    function yolo() {
    }

    function numberOption(value, defaultValue) {
        return value === undefined || value === null ? defaultValue : Number(value);
    }

    function resolveModel(modelPath) {
        if (typeof modelPath !== "string" || modelPath.length === 0) {
            throw new Error("YOLO 模型路径不能为空");
        }
        var assetPrefix = "asset://";
        if (modelPath.indexOf(assetPrefix) === 0) {
            return { asset: true, path: modelPath.substring(assetPrefix.length) };
        }
        return { asset: false, path: runtime.files.path(modelPath) };
    }

    function Detector(nativeDetector, labels) {
        this.nativeDetector = nativeDetector;
        this.labels = labels || [];
    }

    Detector.prototype.detect = function (image, options) {
        options = options || {};
        var confidence = numberOption(options.confidence, 0.25);
        var nms = numberOption(options.nms, 0.45);
        var packed = this.nativeDetector.detect(image, confidence, nms);
        if (packed === null) throw new Error("YOLO 推理失败");

        var result = [];
        // 打包 v2：[preMs, infMs, cols, 每行 cols 个]；cols=6 为检测，6+3K 为姿态（每行带 K 个关键点）。
        // 兼容旧 v1（[preMs, infMs, 每行 6 个]）：第三个字段不满足「合法行宽 + 长度整除」时按 v1 解。
        var stride = 6, start = 2;
        if (packed.length >= 9) {
            var cols = Math.round(Number(packed[2]));
            if (cols >= 6 && cols <= 306 && cols % 3 === 0 && Number(packed[2]) === cols &&
                (packed.length - 3) % cols === 0) {
                stride = cols;
                start = 3;
            }
        }
        for (var i = start; i + stride - 1 < packed.length; i += stride) {
            var left = Number(packed[i]);
            var top = Number(packed[i + 1]);
            var right = Number(packed[i + 2]);
            var bottom = Number(packed[i + 3]);
            var classId = Math.round(Number(packed[i + 5]));
            var item = {
                classId: classId,
                label: this.labels[classId] === undefined ? String(classId) : this.labels[classId],
                score: Number(packed[i + 4]),
                bounds: {
                    left: left,
                    top: top,
                    right: right,
                    bottom: bottom,
                    width: right - left,
                    height: bottom - top,
                    centerX: (left + right) / 2,
                    centerY: (top + bottom) / 2
                }
            };
            if (stride > 6) {
                // 姿态：每行尾部是 K 个 (x, y, score)
                var keypoints = [];
                for (var k = i + 6; k + 2 < i + stride; k += 3) {
                    keypoints.push({
                        x: Number(packed[k]),
                        y: Number(packed[k + 1]),
                        score: Number(packed[k + 2])
                    });
                }
                item.keypoints = keypoints;
            }
            result.push(item);
        }
        result.preprocessMs = packed.length >= 2 ? Number(packed[0]) : 0;
        result.inferenceMs = packed.length >= 2 ? Number(packed[1]) : 0;
        result.totalMs = result.preprocessMs + result.inferenceMs;
        return result;
    };

    Detector.prototype.close = function () {
        this.nativeDetector.close();
    };

    Detector.prototype.isClosed = function () {
        return this.nativeDetector.isClosed();
    };

    function normalizeBackend(backend) {
        backend = String(backend || "opencv").toLowerCase();
        if (backend === "cpu" || backend === "opencv-dnn" || backend === "opencv5" ||
            backend === "dnn") {
            return "opencv";
        }
        return backend;
    }

    yolo.isAvailable = function (backend) {
        return backend === undefined
            ? javaYolo.isAvailable()
            : javaYolo.isAvailable(normalizeBackend(backend));
    };

    yolo.getUnavailableReason = function (backend) {
        return backend === undefined
            ? String(javaYolo.getUnavailableReason())
            : String(javaYolo.getUnavailableReason(normalizeBackend(backend)));
    };

    yolo.getVersion = function (backend) {
        return backend === undefined
            ? String(javaYolo.getVersion())
            : String(javaYolo.getVersion(normalizeBackend(backend)));
    };

    yolo.load = function (options) {
        options = options || {};
        var backend = normalizeBackend(options.backend);
        if (backend !== "opencv") {
            throw new Error("不支持的 YOLO 后端：" + options.backend + "（当前仅支持 opencv）");
        }
        if (!javaYolo.isAvailable(backend)) {
            throw new Error(String(javaYolo.getUnavailableReason(backend)));
        }
        var inputSize = Math.round(numberOption(options.inputSize, 640));
        var defaultThreads = Math.min(4, java.lang.Runtime.getRuntime().availableProcessors());
        var threads = Math.round(numberOption(options.threads, defaultThreads));

        var model = resolveModel(options.model || options.onnx);
        var nativeDetector = model.asset
            ? javaYolo.createOpenCvFromAssets(model.path, inputSize, threads)
            : javaYolo.createOpenCv(model.path, inputSize, threads);
        return new Detector(nativeDetector, options.labels || []);
    };

    return yolo;
};
