// @engine quickjs
"use strict";

/** vision：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    /** 跟服务要一行限频诊断：到底走的哪条路、字形/流水线自己的账怎么样 */
    function logPipelineNote(smart) {
        if (!smart || !smart.ok) {
            return;
        }
        if (smart.method === "glyph") {
            if (Date.now() - panelState.lastPipelineNoteAt <= 2000) {
                return;
            }
            panelState.lastPipelineNoteAt = Date.now();
            const g = smart.glyphStats || {};
            panelActions.logLine(
                "字形识别：触子 " +
                    (g.inked || 0) +
                    "｜未知 " +
                    (smart.unknownCells || 0) +
                    "｜用时 " +
                    (g.ms || 0) +
                    "ms" +
                    (g.retries ? "（复核 " + g.retries + "）" : "") +
                    "｜验证 " +
                    (smart.validation && smart.validation.ok ? "过" : "不过")
            );
            return;
        }
        if (smart.method !== "vision-pipeline") {
            return;
        }
        if (Date.now() - panelState.lastPipelineNoteAt <= 2000) {
            return;
        }
        panelState.lastPipelineNoteAt = Date.now();
        const st = smart.pipelineStats || {};
        const fusion = smart.fusion || {};
        const v = smart.validation || {};
        panelActions.logLine(
            "视觉流水线：规则门 " +
                (v.ok ? "通过" : "否决（" + (v.reason || "未说明") + "）") +
                "｜确认 " +
                (fusion.confirmed || 0) +
                "／未定 " +
                (fusion.unknown || 0) +
                "｜OCR 成功 " +
                (st.ocrOk || 0) +
                "／缓存 " +
                (st.cellsCached || 0) +
                "／框字形打架 " +
                (st.labelMismatch || 0) +
                "／补全 " +
                (st.inferred || 0)
        );
    }

    /**
     * 建（或取）视觉流水线实例（A2：装配逻辑在工厂里，两入口共用）。
     * 面板/服务层仍按原语义调用：失败返回 null（服务层会退回旧路径）。
     */

    function boardRegionSamples(image, g) {
        return panelState.boardRegionSamplesCore(image, g || panelState.grid);
    }

    return { logPipelineNote: logPipelineNote, boardRegionSamples: boardRegionSamples };
}

module.exports = { create: create };
