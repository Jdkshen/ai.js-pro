// @engine quickjs
"use strict";
// 网格候选评分与搜索。分类器由入口注入；生产注入唯一的字形源。
const adapter = require("../runtime/aijs-adapter.js");
const geometry = require("./geometry.js");
const fen = require("../chess/fen.js");
const cells = require("./cells.js");
const gridSearch = require("./grid-search.js");
const detectorSession = require("./detector-session.js");
const loadDetector = detectorSession.loadDetector;
function create(run) {
    function calibrateGrid(image, located, options) {
        const opts = options || {};
        let aborted = false;
        function shouldAbort() {
            if (typeof opts.shouldAbort === "function" && opts.shouldAbort()) aborted = true;
            return aborted;
        }
        // ⚠️ 真机踩过：locateBoardOnScreen() 给的是 `frameObject`，**没有** `imageObject`
        //    （locateBoardInImage 才有）→ 只读 imageObject 会让校准**静默失败**，
        //    而失败后仍然返回、上层继续用未校准的几何（颜色判错、未知格一堆）。
        //    所以这里两个字段都认，并且允许调用方直接传图。
        const frame = image || (located && (located.imageObject || located.frameObject)) || null;
        if (!frame || !located || !located.ok || !located.grid) {
            return { ok: false, reason: "缺少图像（imageObject/frameObject 都没有）或基准几何" };
        }
        const search = opts.search || { maxShift: 18, step: 7 };
        const maxShift = search.maxShift == null ? 18 : search.maxShift;
        const step = search.step == null ? 7 : search.step;
        const maxCandidates = opts.maxCandidates == null ? 25 : opts.maxCandidates;
        /** 粗扫（覆盖半格级错锚）的候选上限：会话级一次，宽容一些没代价 */
        const coarseCandidates = opts.coarseCandidates == null ? 81 : opts.coarseCandidates;

        const base = located.grid;
        const runOpts = Object.assign({}, opts);
        delete runOpts.search;

        let tried = 0;
        /**
         * 丢一个子的代价。单位与「未知格」同量级：一个格子被认错，无论是「读不出来」还是
         * 「读成了空格」，对盘面都是同一个错。
         *
         * ⚠️ 三项权重的相对大小是**真机事故换来的**，别随手调：
         *   · 「修复动作」曾经是 ×1000 → 2026-09-14 K40 实测：正确几何 (0,0) 读出「子 31/未知 1」
         *     但要 1 次棋规修复 → 代价 1001；而一个偏掉 50px（≈0.44 格）的畸形几何虽然把整盘
         *     推成「子 9 / 未知 23」（一盘垃圾），却因为「不需要修复」而代价只有 287 → 胜出、
         *     整个面板就卡在 RECOVERING。
         *   · 而修复降级的格子**本来就已经计进未知格**（repair 会把非法子改成 '?'），所以
         *     修复只需很低的额外权重（区分「模型自己读不出」与「读出矛盾子要去纠正」）。
         *   · 「丢子」必须比未知格重：把棋子读成空格时 unknown 不涨（那格成了 EMPTY），
         *     只能靠这一项把它拉回来。
         */
        const PIECE_LOSS_WEIGHT = 20;
        /** 一次棋规修复的额外代价（降级后的格子已计入 unknown，这里只区分来源）。 */
        const REPAIR_WEIGHT = 6;
        const results = [];

        /** 候选代价：越小越好。pickBest 与诊断落盘共用，避免两处各写一份公式而不同步。 */
        function costOf(item, maxPieces) {
            return item.fixes * REPAIR_WEIGHT + item.unknown + (maxPieces - item.pieces) * PIECE_LOSS_WEIGHT;
        }

        /** 从 layout（FEN 风格字符串）数棋子个数：字母是棋子，数字是空格。 */
        function countPieces(layout) {
            if (!layout) {
                return 0;
            }
            let count = 0;
            for (let i = 0; i < layout.length; i++) {
                const ch = layout.charAt(i);
                if ((ch >= "a" && ch <= "z") || (ch >= "A" && ch <= "Z")) {
                    count += 1;
                }
            }
            return count;
        }

        /**
         * 评估一个偏移；失败会**重试一次**。
         * ⚠️ 冷启动（模型/图刚打开）时第一发可能直接失败，而失败在旧的实现里等价于
         * 「最差候选」（unknown=999）—— 结果就是基准偏移 (0,0) 被淘汰、一个便宜但错的偏移胜出。
         */
        function evaluate(dx, dy) {
            if (shouldAbort()) return null;
            const candidate = Object.assign({}, located, {
                grid: Object.assign({}, base, {
                    originX: base.originX + dx,
                    originY: base.originY + dy
                })
            });
            let result = null;
            for (let attempt = 0; attempt < 2 && result == null; attempt++) {
                if (shouldAbort()) return null;
                try {
                    const current = run(frame, candidate, runOpts, null);
                    if (current && current.ok) {
                        result = current;
                    }
                } catch (error) {
                    result = null;
                }
            }
            if (result == null) {
                return null;
            }
            tried += 1;
            const repairedInfo = result.repaired || {};
            const fixes =
                (result.validation && result.validation.ok === false ? 100 : 0) +
                (repairedInfo.demoted ? repairedInfo.demoted.length : 0) +
                (repairedInfo.flipped ? repairedInfo.flipped.length : 0);
            return {
                ok: result.ok,
                dx: dx,
                dy: dy,
                unknown: result.unknownCells,
                fixes: fixes,
                pieces: countPieces(result.layout),
                cost: 0,
                layout: result.layout,
                grid: candidate.grid,
                sideUnknown: result.stats ? result.stats.sideUnknown : null
            };
        }

        /** 扫一批候选偏移（dx/dy = center + 偏移）；不做「代价 0 就提前收工」：
         *  降级/翻色修复能让多个偏移都凑出代价 0，提前退出会把没对齐的那个留下。 */
        function scanOffsets(centerX, centerY, halfRange, stepPx, cap) {
            const offsets = [];
            for (let dx = -halfRange; dx <= halfRange; dx += stepPx) {
                for (let dy = -halfRange; dy <= halfRange; dy += stepPx) {
                    offsets.push([dx, dy]);
                }
            }
            // 先试偏移小的：真机偏差就在十几像素，大的只作为兜底
            offsets.sort(function (a, b) {
                return Math.abs(a[0]) + Math.abs(a[1]) - (Math.abs(b[0]) + Math.abs(b[1]));
            });
            for (let i = 0; i < offsets.length && i < cap; i++) {
                if (shouldAbort()) break;
                const item = evaluate(centerX + offsets[i][0], centerY + offsets[i][1]);
                if (item) {
                    results.push(item);
                }
            }
        }

        /**
         * 在已评估的候选里挑最优。
         *
         * 代价 = 修复动作 × REPAIR_WEIGHT + 未知格 + **丢子 × PIECE_LOSS_WEIGHT**
         * （权重为什么是这几个数，见 costOf 上面的事故记录）
         */
        function pickBest(list) {
            let maxPieces = 0;
            for (let i = 0; i < list.length; i++) {
                if (list[i].pieces > maxPieces) {
                    maxPieces = list[i].pieces;
                }
            }
            let best = null;
            for (let i = 0; i < list.length; i++) {
                const item = list[i];
                const cost = costOf(item, maxPieces);
                if (
                    best == null ||
                    cost < best.cost ||
                    (cost === best.cost &&
                        Math.abs(item.dx) + Math.abs(item.dy) < Math.abs(best.dx) + Math.abs(best.dy))
                ) {
                    best = Object.assign({}, item, { cost: cost, maxPieces: maxPieces });
                }
            }
            return best;
        }

        // (1) 基准偏移：先用单点扫确认它真被评过（内含重试）
        scanOffsets(0, 0, 0, step, 1);
        // 基准必须通过调用方的独立几何证据，才能跳过粗扫；不能只凭代价低提前接受。
        if (!shouldAbort() && results.length && results[0].unknown === 0 && results[0].fixes === 0 &&
            typeof opts.acceptBaseGrid === "function" && opts.acceptBaseGrid(results[0])) {
            return Object.assign({}, results[0], { tried: tried, earlyAccept: true, reason: "原始几何通过字形与圆心双重校验" });
        }

        // (2) 粗扫：范围覆盖「半格」级错锚 —— 线定位偶尔把第一条线锚错半格（K40 实测偏差 49px），
        //     而调用方习惯传 maxShift=18，那段参数根本够不到真值。步长大 → 候选量可控。
        //     ⚠️ 这一步只有在代价函数看得见「丢子」之后才是安全的（见 pickBest）。
        const cellStep = Math.max(base.stepX || 0, base.stepY || 0);
        const coarseRange = Math.max(maxShift, Math.round(cellStep * 0.75));
        const coarseStep = Math.max(6, Math.round(coarseRange / 3));
        scanOffsets(0, 0, coarseRange, coarseStep, coarseCandidates);

        // (3) 精修：以当前最优为原点，用调用方给的 (maxShift, step) 再扫一遍
        const interim = pickBest(results);
        scanOffsets(interim ? interim.dx : 0, interim ? interim.dy : 0, maxShift, step, maxCandidates);

        // 搜索被中止时不返回「当前最优」；未完整评估的候选不能作为可信几何。
        if (shouldAbort()) return { ok: false, aborted: true, reason: "校准已取消或超过本次时间预算" };

        const best = pickBest(results);

        // 诊断落盘（opts.dumpPath）：把**评估过的全部候选**连同代价写出来。
        // 「校准挑了一个把整盘推向未知的偏移」这种事故，不看候选全量就只能猜。
        if (opts.dumpPath) {
            try {
                let maxPieces = 0;
                for (let i = 0; i < results.length; i++) {
                    if (results[i].pieces > maxPieces) {
                        maxPieces = results[i].pieces;
                    }
                }
                const rows = results
                    .map(function (item) {
                        return {
                            dx: item.dx,
                            dy: item.dy,
                            unknown: item.unknown,
                            pieces: item.pieces,
                            fixes: item.fixes,
                            sideUnknown: item.sideUnknown,
                            cost: costOf(item, maxPieces),
                            layout: item.layout
                        };
                    })
                    .sort(function (a, b) {
                        return a.cost - b.cost || Math.abs(a.dx) + Math.abs(a.dy) - (Math.abs(b.dx) + Math.abs(b.dy));
                    });
                files.write(
                    opts.dumpPath,
                    JSON.stringify({
                        base: { originX: base.originX, originY: base.originY, stepX: base.stepX, stepY: base.stepY },
                        evaluated: results.length,
                        chosen: best ? { dx: best.dx, dy: best.dy, unknown: best.unknown, pieces: best.pieces } : null,
                        candidates: rows
                    })
                );
            } catch (error) {
                // 诊断落盘失败不影响主流程
            }
        }

        if (!best) {
            return { ok: false, reason: "所有候选都识别失败" };
        }
        best.tried = tried;
        best.base = { originX: base.originX, originY: base.originY, stepX: base.stepX, stepY: base.stepY };
        best.reason =
            (best.unknown === 0 ? "已校准（无未知格" : "校准后仍有 " + best.unknown + " 个未知格") +
            (best.fixes ? "；修复动作 " + best.fixes + " 次" : "") +
            "；子 " +
            best.pieces +
            "/" +
            best.maxPieces +
            "）";
        return best;
    }

    function scoreGrid(image, grid, options) {
        const opts = options || {};
        const runOpts = Object.assign({}, opts);
        delete runOpts.search;
        const holder = { ok: true, method: "probe", grid: grid, imageObject: image, frameObject: image };
        let result = null;
        try {
            result = run(image, holder, runOpts, null);
        } catch (error) {
            return null;
        }
        if (!result || !result.ok) {
            return null;
        }
        const counts = cells.count(result.chars || []);
        let violations = 0;
        const parsed = fen.parseFen(result.layout + " w");
        if (!parsed.ok) {
            violations = 9;
        } else {
            violations = fen.validateBoard(parsed.board).errors.length;
        }
        const expect = opts.expectPieces;
        let cost =
            violations * 1000 +
            (result.unknownCells || 0) * 10 +
            counts.invalid.length * 1000 +
            Math.max(0, counts.pieces - 32) * 100;
        if (typeof expect === "number" && isFinite(expect)) {
            cost += Math.abs(counts.pieces - expect) * 20;
        }
        return {
            grid: grid,
            cost: cost,
            pieces: counts.pieces,
            empty: counts.empty,
            unknown: result.unknownCells || 0,
            violations: violations,
            counts: counts,
            result: result
        };
    }

    function pickGrid(image, options) {
        const opts = options || {};
        // 默认种子按**屏幕尺寸**推导（2026-09-22）：旧版写死 K40 坐标（81,666,115,114），
        // 换手机后采样框整体错位 ⇒ 半数检测框落在候选棋盘外（新机 1280×2772 coverage 0.36~0.50 连续拒绝）。
        // 推导失败（拿不到尺寸）才退回 K40 常量兜底。
        const derivedSeeds = gridSearch.deriveSeeds(opts.size || (image ? adapter.sizeOf(image) : null));
        const seeds = opts.seeds ||
            derivedSeeds || [
                { originX: 81, originY: 666, stepX: 115, stepY: 114, orientation: "RED_BOTTOM" },
                { originX: 72, originY: 660, stepX: 114.8, stepY: 114.3, orientation: "RED_BOTTOM" }
            ];
        const orientation = opts.orientation || "RED_BOTTOM";
        const mode = opts.mode === "detect" ? "detect" : "cached";
        const report = {
            mode: mode,
            tried: 0,
            detectCount: 0,
            detectMs: null,
            searchMs: null,
            finalMs: null,
            totalMs: null
        };
        const startedAt = adapter.nowMono();
        if (!image) {
            return { ok: false, reason: "没有图像", tried: 0, report: report };
        }

        // 1) 检测器只加载一次；调用方注入的归调用方，我们不关
        let detector = opts.detector || null;
        let loaded = null;
        let owner = false;
        if (!detector) {
            try {
                loaded = loadDetector(opts);
                detector = loaded.detector;
                owner = true;
            } catch (error) {
                return {
                    ok: false,
                    reason: "加载模型失败：" + (error && error.message ? error.message : error),
                    tried: 0,
                    report: report
                };
            }
        } else {
            loaded = { request: opts.detectorRequest || null, source: "调用方注入", failures: [] };
        }

        const runOpts = Object.assign({}, opts, {
            detector: detector,
            detectorRequest: loaded ? loaded.request : null
        });
        delete runOpts.search;
        delete runOpts.seeds;
        delete runOpts.radius;
        delete runOpts.step;
        delete runOpts.fineRadius;
        delete runOpts.fineStep;
        delete runOpts.radiusRatio;
        delete runOpts.stepScales;
        delete runOpts.minPieces;
        delete runOpts.maxUnknown;
        delete runOpts.requireKings;
        delete runOpts.minCoverage;
        delete runOpts.probeCropMargin;

        try {
            if (mode === "detect") {
                // 对照路径：每候选完整识别（保留下来做性能对比）
                let best = null;
                for (let s = 0; s < seeds.length; s++) {
                    const seed = seeds[s];
                    const radius = opts.radius == null ? 30 : opts.radius;
                    const step = opts.step == null ? 6 : opts.step;
                    for (let dx = -radius; dx <= radius; dx += step) {
                        for (let dy = -radius; dy <= radius; dy += step) {
                            const grid = geometry.createGrid({
                                originX: seed.originX + dx,
                                originY: seed.originY + dy,
                                stepX: seed.stepX,
                                stepY: seed.stepY,
                                orientation: seed.orientation || orientation
                            });
                            const scored = scoreGrid(image, grid, runOpts);
                            report.tried += 1;
                            if (scored && (!best || scored.cost < best.cost)) {
                                best = scored;
                            }
                            if (best && best.cost === 0) {
                                break;
                            }
                        }
                        if (best && best.cost === 0) {
                            break;
                        }
                    }
                }
                report.totalMs = adapter.nowMono() - startedAt;
                if (!best) {
                    return {
                        ok: false,
                        reason: "所有候选都识别失败（detect 模式）",
                        tried: report.tried,
                        report: report
                    };
                }
                return {
                    ok: !!best.result.ok,
                    reason: best.result.ok ? "" : best.result.reason,
                    grid: best.grid,
                    cost: best.cost,
                    tried: report.tried,
                    pieces: best.pieces,
                    empty: best.empty,
                    unknown: best.unknown,
                    counts: best.counts,
                    violations: best.violations,
                    result: best.result,
                    imageObject: image,
                    frameObject: image,
                    method: "pick-grid-detect",
                    report: report
                };
            }

            // 2) 检测一次：裁剪范围必须覆盖**所有种子 ± 搜索半径**（2026-09-22）。
            //    旧版只按 seeds[0] 裁 —— 设备推导的种子有 3 档 y（±5% 屏高），
            //    只锚一个种子会把其它档位棋盘底/顶行的棋子裁掉 ⇒ 边缘格全成未知格。
            const probeGrid =
                gridSearch.probeGridFor(seeds) ||
                geometry.createGrid({
                    originX: seeds[0].originX,
                    originY: seeds[0].originY,
                    stepX: seeds[0].stepX,
                    stepY: seeds[0].stepY,
                    orientation: seeds[0].orientation || orientation
                });
            const probeMargin = opts.probeCropMargin == null ? 0.45 : opts.probeCropMargin;
            const probeOpts = Object.assign({}, runOpts, { cropMargin: probeMargin, autoAlign: false });
            const probe = detectorSession.detectOnly(image, probeGrid, probeOpts);
            report.detectMs = probe && probe.timing ? probe.timing.detectMs : null;
            report.detectCount = probe && probe.detections ? probe.detections.length : 0;
            if (!probe || !probe.ok || !probe.detections || !probe.detections.length) {
                report.totalMs = adapter.nowMono() - startedAt;
                return {
                    ok: false,
                    reason:
                        "首次检测没拿到检测框（棋盘不在画面里？）" + (probe && probe.reason ? "：" + probe.reason : ""),
                    tried: 0,
                    report: report,
                    probe: probe
                };
            }

            const searchStart = adapter.nowMono();
            const found = gridSearch.searchByDetections(probe.detections, {
                seeds: seeds,
                radius: opts.radius,
                step: opts.step,
                fineRadius: opts.fineRadius,
                fineStep: opts.fineStep,
                // 2026-09-22（多证据融合方案 §10 最小版）：原点 ±0.5 格 + 步距 ±2%/±4%
                radiusRatio: opts.radiusRatio,
                stepScales: opts.stepScales,
                orientation: orientation,
                expectPieces: opts.expectPieces,
                expectWeight: opts.expectWeight,
                minPieces: opts.minPieces,
                maxUnknown: opts.maxUnknown,
                requireKings: opts.requireKings,
                requireTypeLimits: opts.requireTypeLimits,
                minCoverage: opts.minCoverage,
                assign: {
                    minScore: opts.minScore,
                    conflictGap: opts.conflictGap,
                    maxResidual: opts.maxResidual,
                    frameQuality: opts.frameQuality
                }
            });
            report.searchMs = adapter.nowMono() - searchStart;
            report.tried = found.tried;
            if (!found.ok) {
                report.totalMs = adapter.nowMono() - startedAt;
                return {
                    ok: false,
                    reason: found.reason,
                    tried: found.tried,
                    candidates: found.candidates,
                    rejectionSummary: found.rejectionSummary,
                    best: found.best,
                    report: report,
                    probe: probe
                };
            }

            // 3) 用胜出的几何做一次完整识别（颜色/一致性修复/布局以它为准）
            const finalStart = adapter.nowMono();
            const finalResult = run(
                image,
                { ok: true, method: "pick-grid", grid: found.grid, imageObject: image, frameObject: image },
                runOpts,
                null
            );
            report.finalMs = adapter.nowMono() - finalStart;
            const counts = cells.count(finalResult && finalResult.chars ? finalResult.chars : []);
            let violations = 0;
            if (finalResult && finalResult.layout) {
                const parsed = fen.parseFen(finalResult.layout + " w");
                violations = parsed.ok ? fen.validateBoard(parsed.board).errors.length : 9;
            } else {
                violations = 9;
            }
            report.totalMs = adapter.nowMono() - startedAt;
            return {
                ok: !!(finalResult && finalResult.ok),
                reason:
                    finalResult && finalResult.ok
                        ? ""
                        : "最终识别失败：" + (finalResult && finalResult.reason ? finalResult.reason : "无结果"),
                grid: found.grid,
                cost: found.cost,
                tried: found.tried,
                pieces: counts.pieces,
                empty: counts.empty,
                unknown: finalResult ? finalResult.unknownCells : null,
                counts: counts,
                violations: violations,
                residual: found.best ? found.best.residual : null,
                coverage: found.best ? found.best.coverage : null,
                result: finalResult,
                imageObject: image,
                frameObject: image,
                method: "pick-grid-cached",
                report: report
            };
        } finally {
            // 所有权：只有我们自己加载的才关；错误/早退/取消都不会漏
            if (owner && detector && typeof detector.close === "function") {
                try {
                    detector.close();
                } catch (error) {
                    // 关不掉的检测器不影响结果
                }
            }
        }
    }
    return { calibrateGrid: calibrateGrid, scoreGrid: scoreGrid, pickGrid: pickGrid };
}
module.exports = { create: create };
