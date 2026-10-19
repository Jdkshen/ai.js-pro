// SPDX-License-Identifier: MIT
/*
 * Node24 OCR 模块（真实内置 OCR 会话适配器）。
 *
 * 后端（都由宿主里的真实现提供，不是 fake）：
 *   - 'ppocr'（默认）：com.stardust.autojs.runtime.api.PPOcr
 *       随包 PP-OCRv6 tiny（ncnn，arm64-v8a），模型在 assets/models/ppocrv6/。
 *   - 'mlkit'：com.stardust.autojs.runtime.api.Ocr
 *       ML Kit 中文文字识别，随包离线模型。
 *
 * 公开接口（与 Node 示例 `文字识别(PaddleOCR).js` 对齐）：
 *   const ocr = await createOCR([options]);
 *   const results = await ocr.detect(image[, detectOptions]);
 *   ocr.release();                  // 同步释放（也返回 boolean；可 await）
 *   ocr.isReleased()
 *   createOCR.liveCount() -> 当前宿主里未释放的会话数
 *
 * image 支持：
 *   - Node 图像句柄：require('image').readImage(...) 或
 *     require('media_projection').requestScreenCapture().nextImage() 返回的 Image；
 *   - 图片绝对/相对路径字符串（相对路径按脚本目录解析，与 image 模块一致）。
 * 不支持 Buffer 像素块；需要先保存成图片或用 image 模块。
 *
 * 识别结果：{text, confidence, rotation, rotationConfidence, bounds}
 *   bounds = {left, top, right, bottom, width, height, centerX, centerY}
 *
 * 旧 `models` 档位处理（明确映射，不静默 fake）：
 *   - 'default' / 'slim' / 'fast' / 'accurate' / 'tiny' / 'mobile' / 'ppocrv6' /
 *     'ppocrv6_tiny' / 'builtin' 都映射到当前唯一随包模型 PP-OCRv6 tiny；
 *   - 自定义模型目录（/... 或含 / 的路径）明确报错：Node24 目前只内置这一套模型；
 *   - 其它未知档位直接报错，不会当作已支持。
 *
 * 异步语义：createOCR/detect 返回 Promise，但宿主桥是同步调用，PP-OCR/ML Kit 的推理仍在
 * Node 线程上执行（Promise 只提供 await/catch，不是后台线程）。
 */
(function () {
  'use strict';

  if (typeof __ajsRegisterModule !== 'function') {
    throw new Error('[Auto.js] ocr 模块必须在 Node24 prelude 之后加载（缺少 __ajsRegisterModule）');
  }

  __ajsRegisterModule('ocr', function (api) {
    var nodePath = require('path');

    var MODEL_ALIASES = {
      default: true,
      slim: true,
      fast: true,
      accurate: true,
      tiny: true,
      mobile: true,
      ppocrv6: true,
      ppocrv6tiny: true,
      builtin: true,
      ppocr: true
    };

    function messageOf(error) {
      if (error && error.message) {
        return String(error.message);
      }
      return String(error);
    }

    function ocrError(message, cause) {
      if (message && message.name === 'AutoJsOcrError') {
        return message;
      }
      var text = message && message.message ? String(message.message) : String(message);
      if (text.indexOf('[Auto.js] ') === 0) {
        text = text.substring(10);
      }
      var wrapped = new Error('[Auto.js] ocr: ' + text);
      wrapped.name = 'AutoJsOcrError';
      if (cause !== undefined) {
        wrapped.cause = cause;
      }
      return wrapped;
    }

    /** 调 kind:"ocr" 宿主；返回 envelope.value，错误统一成 AutoJsOcrError。 */
    function callHost(request) {
      var raw;
      try {
        raw = api.host(request);
      } catch (error) {
        throw ocrError('宿主桥调用失败：' + messageOf(error), error);
      }
      var response;
      if (raw && typeof raw === 'object') {
        response = raw;
      } else {
        try {
          response = JSON.parse(String(raw));
        } catch (error) {
          throw ocrError('宿主桥返回了非法 JSON：' + messageOf(error), error);
        }
      }
      if (!response || response.ok !== true) {
        throw ocrError(response && response.error ? String(response.error) : '宿主返回未知错误');
      }
      return response.value;
    }

    function normalizeBackend(value) {
      if (value == null || value === '') {
        return 'ppocr';
      }
      if (typeof value !== 'string') {
        throw new TypeError('createOCR options.backend 必须是字符串（ppocr/mlkit）');
      }
      var normalized = value.trim().toLowerCase().replace(/[\s_-]+/g, '');
      if (normalized === 'ppocr' || normalized === 'paddle' || normalized === 'paddleocr') {
        return 'ppocr';
      }
      if (normalized === 'mlkit') {
        return 'mlkit';
      }
      throw ocrError('不支持的 OCR backend：' + value + '（支持 ppocr/mlkit）');
    }

    function normalizeModels(value) {
      if (value == null || value === '') {
        return { requested: null, model: 'ppocrv6_tiny' };
      }
      if (typeof value !== 'string') {
        throw new TypeError('createOCR options.models 必须是字符串档位名，或省略使用内置模型');
      }
      var text = value.trim();
      if (text === '') {
        return { requested: null, model: 'ppocrv6_tiny' };
      }
      var normalized = text.toLowerCase().replace(/[\s_-]+/g, '');
      if (MODEL_ALIASES[normalized]) {
        return { requested: text, model: 'ppocrv6_tiny' };
      }
      if (text.charAt(0) === '/' || text.charAt(0) === '.' || text.indexOf('/') >= 0
        || text.indexOf('\\') >= 0) {
        throw ocrError('Node24 内置 OCR 只随包 PP-OCRv6 tiny，不支持自定义 models 目录：' + text);
      }
      throw ocrError('不支持的 OCR models 档位：' + text
        + '（Node24 目前只内置 PP-OCRv6 tiny；可用档位别名 default/slim/fast/accurate/tiny）');
    }

    function integerOption(value, label, fallback) {
      if (value == null) {
        return fallback;
      }
      var number = Number(value);
      if (!Number.isFinite(number) || Math.floor(number) !== number) {
        throw new TypeError('createOCR ' + label + ' 必须是整数');
      }
      if (number < 0) {
        throw new RangeError('createOCR ' + label + ' 不能为负数');
      }
      return number;
    }

    function numberOption(value, label, fallback) {
      if (value == null) {
        return fallback;
      }
      var number = Number(value);
      if (!Number.isFinite(number)) {
        throw new TypeError('createOCR ' + label + ' 必须是有限数字');
      }
      if (number < 0 || number > 1) {
        throw new RangeError('createOCR ' + label + ' 必须在 0..1 之间');
      }
      return number;
    }

    /** Pro 样例常把 cpuPowerMode 写成 LITE_POWER_* 字符串；按真实取值映射。 */
    function normalizeCpuPower(value) {
      if (value == null) {
        return 0;
      }
      if (typeof value === 'number') {
        if (!Number.isFinite(value) || Math.floor(value) !== value) {
          throw new TypeError('createOCR cpuPowerMode 必须是整数或 LITE_POWER_* 字符串');
        }
        return value;
      }
      var text = String(value).trim().toUpperCase().replace(/[\s-]+/g, '_');
      if (text === 'LITE_POWER_NO_BIND' || text === 'NO_BIND' || text === 'DEFAULT') {
        return 0;
      }
      if (text === 'LITE_POWER_LOW' || text === 'LITE_POWER_LITTLE_CORES'
        || text === 'LOW' || text === 'LITTLE' || text === 'LITTLE_CORES') {
        return 1;
      }
      if (text === 'LITE_POWER_HIGH' || text === 'LITE_POWER_FULL'
        || text === 'HIGH' || text === 'FULL') {
        return 2;
      }
      var parsed = parseInt(text, 10);
      if (!isNaN(parsed)) {
        return parsed;
      }
      // 与 Rhino/QuickJS 的旧实现一致：不认识的字符串按 0（不绑核）处理，
      // 但不改变任何识别引擎或模型。
      return 0;
    }

    function rejectPaddleOnlyOptions(options) {
      var keys = ['models', 'cpuPowerMode', 'cpuPower', 'threads', 'parallelThreads',
        'targetSize', 'minScore', 'useFp16', 'recMaxWidth'];
      for (var i = 0; i < keys.length; i++) {
        if (options[keys[i]] != null) {
          throw ocrError('mlkit 后端不支持 PaddleOCR 参数 ' + keys[i]
            + '；请去掉该参数，或使用默认的 ppocr 后端');
        }
      }
    }

    function createSync(options) {
      if (options == null) {
        options = {};
      }
      if (typeof options !== 'object' || Array.isArray(options)) {
        throw new TypeError('createOCR(options) 的 options 必须是对象');
      }

      var backend = normalizeBackend(options.backend);
      var request = {
        kind: 'ocr',
        action: 'create',
        backend: backend
      };
      var requestedModels = null;

      if (backend === 'ppocr') {
        var models = normalizeModels(options.models);
        requestedModels = models.requested;
        request.targetSize = integerOption(options.targetSize, 'targetSize', 0);
        request.cpuPower = normalizeCpuPower(options.cpuPowerMode);
        request.threads = integerOption(
          options.threads != null ? options.threads : options.parallelThreads, 'threads', 0);
        request.minScore = numberOption(options.minScore, 'minScore', 0);
        if (options.useFp16 != null && typeof options.useFp16 !== 'boolean') {
          throw new TypeError('createOCR useFp16 必须是布尔值');
        }
        request.useFp16 = options.useFp16 === true;
        request.recMaxWidth = integerOption(options.recMaxWidth, 'recMaxWidth', 0);
      } else {
        rejectPaddleOnlyOptions(options);
      }

      var value = callHost(request);
      if (!value || typeof value !== 'object') {
        throw ocrError('OCR 宿主 create 返回了非法结果：' + String(value));
      }
      var handle = value.handle;
      if (!Number.isInteger(handle) || handle <= 0) {
        throw ocrError('OCR 宿主 create 返回了非法会话句柄：' + String(handle));
      }

      var state = {
        handle: handle,
        backend: backend,
        released: false
      };

      var session = {
        backend: backend,
        model: backend === 'ppocr' ? 'ppocrv6_tiny' : 'mlkit-chinese',
        requestedModels: requestedModels,
        detect: function (image, detectOptions) {
          return detectSession(state, image, detectOptions);
        },
        release: function () {
          return releaseSession(state);
        },
        close: function () {
          return releaseSession(state);
        },
        isReleased: function () {
          return state.released;
        },
        toString: function () {
          return '[OCR ' + backend + '#' + state.handle
            + (state.released ? ' released' : '') + ']';
        }
      };
      return session;
    }

    function createOCR(options) {
      return Promise.resolve().then(function () {
        return createSync(options);
      });
    }

    function imageDescriptor(image) {
      if (typeof image === 'string') {
        if (image.trim() === '') {
          throw new TypeError('OCR detect 的图片路径不能为空');
        }
        var base = api.scriptFile
          ? nodePath.dirname(api.scriptFile)
          : (api.projectDir || process.cwd());
        return { imagePath: nodePath.isAbsolute(image)
          ? nodePath.normalize(image)
          : nodePath.resolve(base, image) };
      }
      if (Buffer.isBuffer(image) || image instanceof Uint8Array) {
        throw new TypeError('OCR detect 不支持 Buffer/Uint8Array；'
          + '请用 image.readImage()/截图得到的 Image 句柄，或先保存为图片文件');
      }
      if (!image || typeof image !== 'object') {
        throw new TypeError('OCR detect 需要 Image 句柄（含 id）或图片路径字符串');
      }
      var descriptor = image;
      if (typeof image.toJSON === 'function') {
        try {
          descriptor = image.toJSON();
        } catch (error) {
          throw ocrError('读取图片句柄失败（可能已 recycle）：' + messageOf(error), error);
        }
      }
      var id = descriptor && descriptor.id != null ? descriptor.id : descriptor && descriptor._id;
      if (!Number.isInteger(id) || id <= 0) {
        throw new TypeError('OCR detect 的 Image 句柄缺少有效 id（收到 ' + String(id) + '）');
      }
      return { imageId: id };
    }

    function regionOption(region) {
      if (region == null) {
        return null;
      }
      var values;
      if (Array.isArray(region)) {
        if (region.length !== 2 && region.length !== 4) {
          throw new TypeError('OCR detect region 数组必须是 [x,y] 或 [x,y,width,height]');
        }
        values = region;
      } else if (typeof region === 'object') {
        if (region.x == null || region.y == null) {
          throw new TypeError('OCR detect region 对象需要 x/y（可选 width/height）');
        }
        values = region.width != null && region.height != null
          ? [region.x, region.y, region.width, region.height]
          : [region.x, region.y];
      } else {
        throw new TypeError('OCR detect region 必须是数组或 {x,y,width,height}');
      }
      var result = [];
      for (var i = 0; i < values.length; i++) {
        var number = Number(values[i]);
        if (!Number.isFinite(number) || Math.floor(number) !== number || number < 0) {
          throw new RangeError('OCR detect region[' + i + '] 必须是非负整数');
        }
        result.push(number);
      }
      if (result.length === 4 && (result[2] <= 0 || result[3] <= 0)) {
        throw new RangeError('OCR detect region 的 width/height 必须大于 0');
      }
      return result;
    }

    function detectOptions(request, options) {
      if (options == null) {
        options = {};
      }
      if (typeof options !== 'object' || Array.isArray(options)) {
        throw new TypeError('OCR detect options 必须是对象');
      }
      request.max = integerOption(options.max, 'detect.max', 1000);
      if (options.detectRotation != null && typeof options.detectRotation !== 'boolean') {
        throw new TypeError('OCR detect detectRotation 必须是布尔值');
      }
      request.detectRotation = options.detectRotation === true;
      var region = regionOption(options.region);
      if (region != null) {
        request.region = region;
      }
    }

    function normalizeResult(item) {
      if (!item || typeof item !== 'object') {
        throw ocrError('OCR 宿主返回了非法结果项：' + String(item));
      }
      var bounds = null;
      if (item.bounds && typeof item.bounds === 'object') {
        bounds = {
          left: Number(item.bounds.left) || 0,
          top: Number(item.bounds.top) || 0,
          right: Number(item.bounds.right) || 0,
          bottom: Number(item.bounds.bottom) || 0,
          width: Number(item.bounds.width) || 0,
          height: Number(item.bounds.height) || 0,
          centerX: Number(item.bounds.centerX) || 0,
          centerY: Number(item.bounds.centerY) || 0
        };
      }
      var confidence = Number(item.confidence);
      var rotation = Number(item.rotation);
      var rotationConfidence = Number(item.rotationConfidence);
      return {
        text: item.text == null ? '' : String(item.text),
        confidence: Number.isFinite(confidence) ? confidence : 0,
        rotation: Number.isFinite(rotation) ? rotation : 0,
        rotationConfidence: Number.isFinite(rotationConfidence) ? rotationConfidence : 0,
        bounds: bounds
      };
    }

    function detectSession(state, image, options) {
      return Promise.resolve().then(function () {
        if (state.released) {
          throw ocrError('OCR 会话已 release()，不能继续 detect');
        }
        var request = {
          kind: 'ocr',
          action: 'detect',
          handle: state.handle
        };
        var descriptor = imageDescriptor(image);
        if (descriptor.imageId != null) {
          request.imageId = descriptor.imageId;
        } else {
          request.imagePath = descriptor.imagePath;
        }
        detectOptions(request, options);
        var value = callHost(request);
        if (value == null) {
          return [];
        }
        if (!Array.isArray(value)) {
          throw ocrError('OCR detect 宿主返回了非数组结果');
        }
        var results = [];
        for (var i = 0; i < value.length; i++) {
          results.push(normalizeResult(value[i]));
        }
        return results;
      });
    }

    function releaseSession(state) {
      if (state.released) {
        return false;
      }
      // 先置位，保证重复 release 幂等；真正的资源释放由宿主 ocrRelease 完成。
      state.released = true;
      var value = callHost({
        kind: 'ocr',
        action: 'release',
        handle: state.handle
      });
      return value === true;
    }

    function liveCount() {
      return callHost({ kind: 'ocr', action: 'liveCount' });
    }

    return {
      createOCR: createOCR,
      create: createOCR,
      liveCount: liveCount
    };
  });
})();
