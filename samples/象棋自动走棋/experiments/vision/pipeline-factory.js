// @engine quickjs
"use strict";
// 显式对照实验入口；生产启动不会加载。
const visionPipelineModule=require("./vision-pipeline.js");
const current=require("../../src/vision/pipeline-factory.js");
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
module.exports=Object.assign({},current,{create:create});
