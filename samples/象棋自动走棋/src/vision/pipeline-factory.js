// @engine quickjs
"use strict";
// 生产视觉装配：唯一棋子分类源为 glyph；OCR holder 仅供结算文字/显式诊断。
function createOcrHolder(deps) {
    const d = deps || {};
    const log = typeof d.log === "function" ? d.log : function () {};
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

function createGlyphSource(deps) {
    const d = deps || {};
    const log = typeof d.log === "function" ? d.log : function () {};
    const glyph = require("./glyph-recognizer.js");
    const glyphResolve = require("./glyph-resolve.js");
    const circleEvidence = require("./circle-evidence.js");
    const boardValidator = require("./board-validator.js");
    const fenModule = require("../chess/fen.js");
    const reuseGateModule = require("./reuse-gate.js");
    const dir = String(d.baseDir || ".").replace(/\/+$/, "") + "/assets/glyph-tpl";
    let templates = null;
    let loadError = "";
    let tried = false;
    /** 上一次打过的「未知格明细」行（与救援一样：只在变了的时候打，不刷屏） */
    let lastNoteLine = "";
    /**
     * ★ 「识别直通」闸门（2026-10-06，用户口径「识别省 300ms」）：找色指纹没变的格沿用上一帧结论。
     *   没有 countColor 能力的宿主（离线桩/假宿主）自动失能 —— 行为与从前一字不差。
     */
    const reuseGate = reuseGateModule.create({
        countColor: d.adapter && typeof d.adapter.countColor === "function"
            ? function (image, color, options) { return d.adapter.countColor(image, color, options); }
            : null,
        nowMono: d.adapter && typeof d.adapter.nowMono === "function"
            ? function () { return d.adapter.nowMono(); }
            : function () { return Date.now(); },
        log: log
    });

    /** 抢救/未知格的明细 → 一句人能看懂的话（坐标用「行/列」口径：r7c4）。 */
    function describeNotes(notes) {
        const parts = [];
        for (let i = 0; i < notes.length; i++) {
            const n = notes[i];
            const at = "r" + n.row + "c" + n.col;
            if (n.kind === "unknown") {
                if (n.gate === "side") {
                    parts.push(
                        at +
                            " 认得出「" +
                            n.name +
                            "」但分不出红黑（分 " +
                            (n.sim == null ? "?" : Number(n.sim).toFixed(2)) +
                            "）"
                    );
                } else {
                    parts.push(
                        at +
                            " 没读出来：最佳「" +
                            (n.name || "无") +
                            "」" +
                            (n.sim == null ? "" : Number(n.sim).toFixed(2)) +
                            (n.second ? "，次名「" + n.second + "」" + Number(n.secondSim).toFixed(2) : "")
                    );
                }
            } else if (n.kind === "rescued" || n.kind === "held") {
                parts.push(
                    at +
                        " 抢救成「" +
                        n.name +
                        "」（" +
                        Number(n.sim).toFixed(2) +
                        "，×" +
                        n.scale +
                        (n.was
                            ? "，原本是「" + n.was + "」" + (n.wasSim == null ? "" : Number(n.wasSim).toFixed(2))
                            : "") +
                        "）"
                );
            }
        }
        return parts.join("；");
    }

    function makeHost() {
        if (d.host) {
            return d.host;
        }
        return {
            sizeOf: function (image) {
                return d.adapter.sizeOf(image);
            },
            grayscale: function (image) {
                return images.grayscale(image);
            },
            clip: function (image, x, y, w, h) {
                return images.clip(image, x, y, w, h);
            },
            threshold: function (image, t, max) {
                return images.threshold(image, t, max);
            },
            countColor: function (image, color, options) {
                return d.adapter.countColor(image, color, options);
            },
            matchTemplate: function (image, template, options) {
                return images.matchTemplate(image, template, options);
            },
            pixel: function (image, x, y) {
                return images.pixel(image, x, y);
            },
            red: function (pixel) {
                return colors.red(pixel);
            },
            green: function (pixel) {
                return colors.green(pixel);
            },
            blue: function (pixel) {
                return colors.blue(pixel);
            },
            resize: function (image, size) {
                return images.resize(image, size);
            },
            read: function (path) {
                return images.read(path);
            },
            nowMono: function () {
                return d.adapter.nowMono();
            }
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
            // ★ 「识别直通」（见顶部 reuseGate）——先算指纹、比缓存，再看要不要跑识别：
            //   · 全同 ⇒ reprise：整帧直通（连识别都不跑）；
            //   · 部分变 ⇒ adopt：只把变化的格送去重认，其余格沿用；
            //   · options.fresh（杀棋复核 freshRead / independentRead）⇒ 必须真读，沿用一律让路。
            let decision = null;
            try { decision = reuseGate.decide(image, grid, options || {}); }
            catch (error) { decision = null; }
            if (decision && decision.mode === "reprise") {
                const hit = reuseGate.repriseResult(decision);
                if (hit) {
                    return hit;
                }
            }
            const host = makeHost();
            const scale = glyph.scaleFor(grid);
            const prepared = glyph.prepareFor(host, templates, scale);
            const started = host.nowMono();
            let rec = null;
            try {
                rec = glyph.recognize(host, image, grid, {
                    templates: prepared.templates,
                    TW: prepared.TW,
                    ANCHOR_DY: prepared.ANCHOR_DY,
                    coarseTemplates: prepared.coarse,
                    referenceBoard: options && options.referenceBoard,
                    collect: true, // 逐格分数：盘面级判决（降级/补全）要挑「最弱格」
                    // ★ 指纹没变的格：直接沿用上一帧结论（跳过三道门 + 模板匹配）；
                    //   没有可沿用的格时是 null，行为与从前一字不差。
                    adopt: decision && decision.mode === "adopt" ? decision.adopt : null
                });
            } catch (error) {
                return { ok: false, reason: "字形识别异常：" + (error && error.message ? error.message : error) };
            }
            if (!rec || rec.error) {
                return { ok: false, reason: "字形识别异常：" + ((rec && rec.error) || "没有结果") };
            }
            const rawBoard = glyph.normalizeBoard(rec.chars);
            const circles =
                d.adapter && typeof d.adapter.detectXiangqiCircles === "function"
                    ? d.adapter.detectXiangqiCircles(image, grid)
                    : null;
            const checked = circleEvidence.verify(rawBoard, circles, options && options.referenceBoard);
            const preserved = glyph.preserveIdentityOnSameOccupancy(checked.board, options && options.referenceBoard);
            // ★ 盘面级判决（2026-09-25）：把定位那套「全局拟合 + 容忍个别缺失」搬到判决层——
            //   逐格独立判过的读数再过一遍「子力上限 + 棋规」：① 违规格降级/改阵营；
            //   ② 「字形认得、只差红黑」的格子在唯一解时补上。详见 glyph-resolve.js。
            const scores = {};
            (rec.cells || []).forEach(function (item) {
                if (item && item.idx != null && item.sim != null) {
                    scores[item.idx] = item.sim;
                }
            });
            const resolved = glyphResolve.resolve(preserved.board, {
                scores: scores,
                notes: rec.notes || []
            });
            const board = resolved.chars;
            // ★ 2026-09-25：把「抢救了什么 / 哪一格为什么没读出来」说给面板听。
            //   未知格明细只在变化时打（帧频 ~1/s，不变就不刷屏）；抢救本身很少，见到就打。
            const notes = rec.notes || [];
            if (notes.length) {
                const text = describeNotes(notes);
                if (rec.rescued > 0) {
                    log(
                        "字形抢救 " +
                            rec.rescued +
                            " 格：" +
                            text +
                            "（抢救 " +
                            rec.rescues +
                            " 次，耗时 " +
                            Math.round(rec.rescueMs || 0) +
                            "ms）"
                    );
                }
                const unknownText = describeNotes(
                    notes.filter(function (n) {
                        return n.kind === "unknown";
                    })
                );
                if (unknownText && unknownText !== lastNoteLine) {
                    lastNoteLine = unknownText;
                    log("字形未知格：" + unknownText);
                }
            } else {
                lastNoteLine = "";
            }
            // 盘面级判决的「动了哪几格」：补全/降级/改阵营都是少数事件，逐条说清楚
            if (resolved.filled.length || resolved.demoted.length || resolved.flipped.length) {
                const parts = [];
                resolved.filled.forEach(function (item) {
                    parts.push(
                        "r" +
                            item.row +
                            "c" +
                            item.col +
                            " 守恒补全 →「" +
                            item.char +
                            "」（" +
                            item.why +
                            (item.sim == null ? "" : "，分 " + Number(item.sim).toFixed(2)) +
                            "）"
                    );
                });
                resolved.demoted.forEach(function (item) {
                    parts.push(
                        "r" + item.row + "c" + item.col + " 违规降级为未知（" + (item.reason || "未说明") + "）"
                    );
                });
                resolved.flipped.forEach(function (item) {
                    parts.push("r" + item.row + "c" + item.col + " 改阵营 " + item.from + "→" + item.to);
                });
                log("字形判决 " + parts.length + " 处：" + parts.join("；"));
            }
            const unknownCells = glyph.countUnknown(board);
            let layout = "";
            try {
                layout = fenModule.layoutOf(board, { allowUnknown: true });
            } catch (error) {
                layout = "";
            }
            const validation = boardValidator.validate(board, {
                allowUnknown: true,
                side: options && options.side
            });
            if (validation.ok && unknownCells === 0 && typeof d.confirmGeometry === "function") {
                try { d.confirmGeometry(image, grid); }
                catch (error) { log("SIFT 几何缓存未更新：" + error); }
            }
            const out = {
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
                    adopted: rec.adopted || 0,
                    retries: rec.retries,
                    rescued: rec.rescued || 0,
                    rescues: rec.rescues || 0,
                    rescueMs: Math.round(rec.rescueMs || 0),
                    notes: notes,
                    resolve: resolved.stats,
                    resolvedFilled: resolved.filled,
                    resolvedDemoted: resolved.demoted,
                    resolvedFlipped: resolved.flipped,
                    heldIdentities: preserved.held,
                    circleEvidence: { active: checked.active, circles: checked.circles, conflicts: checked.conflicts, sides: checked.sides || {} },
                    profile: rec.profile || null
                }
            };
            // ★ 识别直通落账：指纹 + 可沿用表 +（够干净时）结果快照，供下一帧 decide 用。
            try { reuseGate.commit(grid, options || {}, rec, out); }
            catch (error) { /* 落账失败不影响本次结论 */ }
            return out;
        },
        release: function () {
            glyph.releaseTemplates(templates);
            templates = null;
            tried = false;
            loadError = "";
        },
        describe: function () {
            return templates ? "字形识别（电脑 py 移植，11 模板）" : "字形识别（未就绪）";
        }
    };
    if (typeof events !== "undefined" && events && typeof events.on === "function") {
        events.on("exit", source.release);
    }
    return source;
}
module.exports = {
    createOcrHolder: createOcrHolder,
    createSamples: createSamples,
    createGlyphSource: createGlyphSource
};
