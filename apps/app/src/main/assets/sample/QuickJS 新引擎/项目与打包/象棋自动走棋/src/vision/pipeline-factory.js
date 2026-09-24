// @engine quickjs
/**
 * 视觉流水线装配（两入口共用，A2 视觉收口）。
 *
 * 为什么要有这个模块：此前「OCR 会话 → 常驻 detect-only YOLO → vision-pipeline 实例」这组
 * 懒加载装配逻辑只有悬浮窗面板有；主界面因此一直走旧识别路径（`recognizeSmart` 没有 vision 注入
 * 就只能 legacy）。这里把装配抽成一份，能力仍由入口注入（adapter/recognizeBoard/几何/日志）。
 *
 * 三条真机约束（沿用面板侧踩过的结论）：
 *   · 检测必须复用 `recognize-board` 的 detect-only 会话（它做了宽高比处理；直接 adapter.detect
 *     会撞 reshape 断言）；
 *   · 原生框是 `{bounds:{centerX,...}}` 形态，统一由 `piece-detector` 处理（流水线内部）；
 *   · OCR 会话**共用**（引擎是进程内单例，多建只是浪费）——由入口通过 getOcrSession 提供。
 */
"use strict";

const visionPipelineModule = require("./vision-pipeline.js");

/**
 * OCR 会话持有者（懒建、复用、可释放）。宿主没有 `$ocr` 时 get() 返回 null。
 * @param deps { log }
 */
function createOcrHolder(deps) {
    const d = deps || {};
    const log = typeof d.log === "function" ? d.log : function () { };
    let session = null;
    let tried = false;
    return {
        get: function () {
            if (session || tried) {
                return session;
            }
            tried = true;
            try {
                if (typeof $ocr === "undefined" || typeof $ocr.create !== "function") {
                    log("单格 OCR 不可用：宿主没有 $ocr");
                    return null;
                }
                session = $ocr.create({ backend: "ppocr" });
                log(session ? "单格 OCR 已启用（PP-OCRv6，随 APK 打包）" : "单格 OCR 会话建立失败");
            } catch (error) {
                log("单格 OCR 初始化失败：" + error);
                session = null;
            }
            return session;
        },
        release: function () {
            try {
                if (session && session.release) {
                    session.release();
                }
            } catch (ignore) {
                // 释放失败不影响结论
            }
            session = null;
            tried = false;
        }
    };
}

/**
 * 稳定帧采样器（frame-gate 的输入）。
 *
 * ⚠️ 必须返回**数字数组**（每个点取 R/G/B 均值当灰度）：`frame-gate.meanAbsDiff` 做的是
 *    `Number(a[i]) - Number(b[i])`，传 `[r,g,b]` 三元组会变成 `Number([1,2,3])` = NaN
 *    ⇒ 判定永远报“平均差 NaN”（2026-09-20 两个探针都踩了）。
 * @param adapter 宿主适配层（rgbAt）
 */
function createSamples(adapter) {
    return function (image, grid) {
        if (!grid || !image || typeof adapter.rgbAt !== "function") {
            return null;
        }
        const step = Math.max(24, Math.round(Math.min(Math.abs(grid.stepX), Math.abs(grid.stepY)) / 2));
        const x0 = Math.round(grid.originX - step);
        const y0 = Math.round(grid.originY - step);
        const x1 = Math.round(grid.originX + 8 * grid.stepX + step);
        const y1 = Math.round(grid.originY + 9 * grid.stepY + step);
        const out = [];
        for (let y = y0; y <= y1; y += step) {
            for (let x = x0; x <= x1; x += step) {
                const px = adapter.rgbAt(image, x, y);
                if (!px) {
                    continue;
                }
                out.push((Number(px[0]) + Number(px[1]) + Number(px[2])) / 3);
            }
        }
        return out.length ? out : null;
    };
}

/**
 * 建「流水线装配器」（懒加载；失败返回 null，由服务层退回旧路径）。
 * @param deps {
 *   adapter,                 // clip/rgbAt/countColor/prepareRegion/releaseFrame
 *   recognizeBoard,          // tools/recognize-board.js（createDetectionSession）
 *   getGrid?,                // 调用时取当前几何（detect 时传给会话）
 *   getOcrSession,           // ⇒ OCR 会话（null 则不建流水线）
 *   isGrayEnabled?,          // 灰度预处理开关（false 时**连能力都不给**流水线，A/B 语义）
 *   getOcrPrimary?,          // "gray" | "color"
 *   getDetector?/setDetector?, // 复用入口自己的检测会话（可选；不传则内部建）
 *   log,                     // 装配级日志
 *   pipelineLog?             // 流水线内部日志（入口自行过滤；不传则丢弃）
 * }
 */
function create(deps) {
    const d = deps || {};
    const log = typeof d.log === "function" ? d.log : function () { };
    const pipelineLog = typeof d.pipelineLog === "function" ? d.pipelineLog : function () { };
    let pipeline = null;
    let detectorSession = null;

    function getDetector() {
        return d.getDetector ? d.getDetector() : detectorSession;
    }

    function setDetector(session) {
        if (d.setDetector) {
            d.setDetector(session);
        } else {
            detectorSession = session;
        }
    }

    function ensure() {
        if (pipeline) {
            return pipeline;
        }
        const session = d.getOcrSession ? d.getOcrSession() : null;
        if (!session) {
            log("视觉流水线不可用：没有 OCR 会话");
            return null;
        }
        if (!getDetector()) {
            try {
                setDetector(d.recognizeBoard.createDetectionSession({ autoAlign: false }));
            } catch (error) {
                log("视觉流水线不可用：YOLO 检测会话初始化失败 " + error);
                return null;
            }
        }
        try {
            pipeline = visionPipelineModule.create({
                detect: function (image, detectOptions) {
                    const detected = getDetector().detect(image, d.getGrid ? d.getGrid() : null, detectOptions || {});
                    if (!detected || !detected.ok) {
                        throw new Error((detected && detected.reason) || "detect-only 没有结果");
                    }
                    // 保留宿主原始框；piece-detector 是唯一负责统一框形态与排序截断的模块。
                    return detected.raw || [];
                },
                clip: function (image, box) { return d.adapter.clip(image, box); },
                rgbAt: function (image, x, y) { return d.adapter.rgbAt(image, x, y); },
                // 红黑判色优先走引擎找色（数框内红/黑墨迹）；拿不到能力时流水线会自己退回采样。
                countColor: function (image, color, options) { return d.adapter.countColor(image, color, options); },
                // OCR 预处理（2026-09-21「先灰度」）：主读默认就把格子裁出来转灰度再认；
                // ⚠️ 关掉时**连能力都不给**流水线（而不是给个高门槛）：A/B 对照要的是“一次都不用”。
                prepareRegion: (d.isGrayEnabled && d.isGrayEnabled())
                    ? function (image, region, mode) { return d.adapter.prepareRegion(image, region, mode); }
                    : undefined,
                releaseFrame: function (frame) { return d.adapter.releaseFrame(frame); },
                ocrSession: session,
                log: pipelineLog
            }, { ocrPrimary: d.getOcrPrimary ? d.getOcrPrimary() : "gray" });
        } catch (error) {
            log("视觉流水线创建失败：" + error);
            pipeline = null;
            return null;
        }
        log("视觉流水线已启用（常驻 detect-only YOLO + PP-OCRv6 + 双中心/多尺度 OCR + 逐格变化缓存）");
        return pipeline;
    }

    return {
        ensure: ensure,
        /** 检测会话失效/长时间停用后重建（入口在释放会话处调用）。 */
        reset: function () {
            setDetector(null);
            pipeline = null;
        },
        release: function () {
            setDetector(null);
            pipeline = null;
        }
    };
}

/**
 * 建「字形识别源」——「新的为主」（2026-09-24）的识别通道：电脑 py 移植的
 * `glyph-recognizer.js` + 模板加载（assets/glyph-tpl）+ 规则门校验 + 盘面契约归一化。
 *
 * 两入口（面板 / 主界面）共用；`board-service.recognizeSmart(mode="auto")` 会先跑它，
 * 校验不过（规则门否决 / 未知格超限）才退回流水线→旧路径。
 *
 * @param deps {
 *   adapter,   // sizeOf / countColor / nowMono
 *   baseDir,   // 工程根目录（assets/glyph-tpl 所在）；默认 "."
 *   host?,     // 测试注入：覆盖默认的 images/colors 宿主映射
 *   enabled?,  // 默认 true；false = 服务层不走字形（autorun 可关）
 *   log?
 * }
 * @return { enabled, ready(), recognize(image, grid, options), describe() }
 */
function createGlyphSource(deps) {
    const d = deps || {};
    const log = typeof d.log === "function" ? d.log : function () { };
    const glyph = require("./glyph-recognizer.js");
    const boardValidator = require("./board-validator.js");
    const fenModule = require("../chess/fen.js");
    const dir = String(d.baseDir || ".").replace(/\/+$/, "") + "/assets/glyph-tpl";
    let templates = null;
    let loadError = "";
    let tried = false;

    function makeHost() {
        if (d.host) {
            return d.host;
        }
        return {
            sizeOf: function (image) { return d.adapter.sizeOf(image); },
            grayscale: function (image) { return images.grayscale(image); },
            clip: function (image, x, y, w, h) { return images.clip(image, x, y, w, h); },
            threshold: function (image, t, max) { return images.threshold(image, t, max); },
            countColor: function (image, color, options) { return d.adapter.countColor(image, color, options); },
            matchTemplate: function (image, template, options) { return images.matchTemplate(image, template, options); },
            pixel: function (image, x, y) { return images.pixel(image, x, y); },
            red: function (pixel) { return colors.red(pixel); },
            green: function (pixel) { return colors.green(pixel); },
            blue: function (pixel) { return colors.blue(pixel); },
            resize: function (image, size) { return images.resize(image, size); },
            read: function (path) { return images.read(path); },
            nowMono: function () { return d.adapter.nowMono(); }
        };
    }

    const source = {
        enabled: d.enabled !== false,
        /** 模板懒加载（第一次识别时）；加载失败只记一次，不再重试。 */
        ready: function () {
            if (templates || tried) {
                return !!templates;
            }
            tried = true;
            try {
                templates = glyph.loadTemplates(makeHost(), dir);
                log("字形识别已启用（11 模板：" + dir + "）");
            } catch (error) {
                loadError = String(error && error.message ? error.message : error);
                log("字形识别不可用：" + loadError);
            }
            return !!templates;
        },
        /**
         * 识别一帧 → 工程盘面契约（空格 = null、"?" 未知；带 validation/layout/unknownCells/stats）。
         * @return { ok:true, chars, ... } | { ok:false, reason }
         */
        recognize: function (image, grid, options) {
            if (source.enabled === false) {
                return { ok: false, reason: "字形识别已关闭" };
            }
            if (!source.ready()) {
                return { ok: false, reason: "字形模板不可用：" + loadError };
            }
            const host = makeHost();
            const scale = glyph.scaleFor(grid);
            const prepared = glyph.prepareFor(host, templates, scale);
            const started = host.nowMono();
            let rec = null;
            try {
                rec = glyph.recognize(host, image, grid, {
                    templates: prepared.templates, TW: prepared.TW, ANCHOR_DY: prepared.ANCHOR_DY,
                    coarseTemplates: prepared.coarse,
                    referenceBoard: options && options.referenceBoard
                });
            } catch (error) {
                return { ok: false, reason: "字形识别异常：" + (error && error.message ? error.message : error) };
            }
            if (!rec || rec.error) {
                return { ok: false, reason: "字形识别异常：" + ((rec && rec.error) || "没有结果") };
            }
            const rawBoard = glyph.normalizeBoard(rec.chars);
            const preserved = glyph.preserveIdentityOnSameOccupancy(rawBoard,
                options && options.referenceBoard);
            const board = preserved.board;
            const unknownCells = glyph.countUnknown(board);
            let layout = "";
            try {
                layout = fenModule.layoutOf(board, { allowUnknown: true });
            } catch (error) {
                layout = "";
            }
            const validation = boardValidator.validate(board, {
                allowUnknown: true, side: options && options.side
            });
            return {
                ok: true,
                method: "glyph",
                chars: board,
                rawChars: rawBoard,
                unknownCells: unknownCells,
                layout: layout,
                validation: validation,
                scale: scale,
                stats: {
                    ms: host.nowMono() - started,
                    recognizeMs: rec.ms,
                    inked: rec.inked,
                    retries: rec.retries,
                    heldIdentities: preserved.held,
                    profile: rec.profile || null
                }
            };
        },
        describe: function () {
            return templates ? "字形识别（电脑 py 移植，11 模板）" : "字形识别（未就绪）";
        }
    };
    return source;
}

module.exports = {
    create: create,
    createOcrHolder: createOcrHolder,
    createSamples: createSamples,
    createGlyphSource: createGlyphSource
};
