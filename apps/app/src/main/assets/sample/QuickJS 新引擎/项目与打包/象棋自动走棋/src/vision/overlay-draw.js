// @engine quickjs
/**
 * 识别绘制：把「棋盘几何 + 识别结果 + 本手计划」编成画板能画的 **框 + 标签** 列表。
 *
 * 为什么要它：驱动出问题时日志只能告诉我「第 N 行第 M 列是 ?」，看不出到底是哪一类错：
 *   · 网格没对齐（所有框整体偏了）      → 看网格线与棋子是否同心
 *   · 某个子漏检 / 同一子被检测两次     → 看原始检测框
 *   · 兵种或颜色读错（高亮环装饰）      → 看每格上的字
 * 把这三样画在屏幕上，人一眼就能审 —— 这是「模型域差」这类问题最快的定位方式。
 *
 * ⚠️ 画板是**系统悬浮窗**（TYPE_APPLICATION_OVERLAY），`screencap` 会**把它一起截进去**，
 * 所以抓帧前必须先 `drawing.hide()`（面板的 grabFrame 已经处理），否则绿框和文字会被
 * 当成棋盘上的东西、直接把识别搞坏。
 *
 * ⚠️ 画板能画的原语只有**矩形 + 一行标签**（颜色写死在 Java 侧），所以状态只能靠标签文字区分：
 *   `?`           未知格（棋规/冲突降级，或模型没认出来）
 *   `X!`          与「期望盘面」不一致（期望是别的子 / 期望有子却读成空）
 *   `·`           期望该有子、却读成空格（只在给了期望盘面时画）
 *   裸框（无标签） 网格线、以及"读成空的原检测框"
 */
"use strict";

const DEFAULTS = {
    /** 网格线用多细的框画（px）。 */
    lineThickness: 3,
    /** 网格线两端各伸出多少 px（棋盘外框一般比"九/十条线"再宽一点）。 */
    linePadding: 24,
    /** 每格标签框的边长（px）；画板把标签画在框左上角 +7px 处。 */
    cellBoxSize: 16,
    /** 检测框缺失宽高时的兜底尺寸（棋子圆盘直径约 96px）。 */
    defaultBoxSize: 88,
    /** 空格子要不要也画（只画框、不带标签）；"full" 模式下才开。 */
    drawEmptyCells: false,
    /** 最多画多少个原始检测框（防手滑把画板塞爆）。 */
    maxDetections: 80
};

/**
 * 棋盘 9 列 × 10 行的**线**（不是格子中心）→ 细长矩形。
 * 这是判断「几何有没有对齐」最直观的东西：线应该正好穿过棋子圆心。
 */
function gridBoxes(grid, options) {
    const cfg = options || {};
    const thickness = cfg.lineThickness == null ? DEFAULTS.lineThickness : cfg.lineThickness;
    const pad = cfg.linePadding == null ? DEFAULTS.linePadding : cfg.linePadding;
    const items = [];
    if (!grid || !isFinite(grid.originX) || !isFinite(grid.stepX)) {
        return items;
    }
    const half = thickness / 2;
    const x1 = grid.originX - pad;
    const x2 = grid.originX + grid.stepX * 8 + pad;
    const y1 = grid.originY - pad;
    const y2 = grid.originY + grid.stepY * 9 + pad;
    for (let row = 0; row < 10; row++) {
        const y = grid.originY + grid.stepY * row;
        items.push({ x1: x1, y1: y - half, x2: x2, y2: y + half, label: "", score: 1 });
    }
    for (let col = 0; col < 9; col++) {
        const x = grid.originX + grid.stepX * col;
        items.push({ x1: x - half, y1: y1, x2: x + half, y2: y2, label: "", score: 1 });
    }
    return items;
}

/** 某格中心的屏幕坐标。 */
function centerOf(grid, row, col) {
    return {
        x: grid.originX + grid.stepX * col,
        y: grid.originY + grid.stepY * row
    };
}

/**
 * 每格的识别结果 → 小方框 + 一个字的标签。
 *   · 有子 → 画那个字（大写=红方 / 小写=黑方，与 FEN 一致），框上带该格的置信度
 *   · `?`（未知）→ 画 `?`
 *   · 该格有两个候选打不出高下（conflict）→ 标签前加 `?!`
 *   · 给了 `expectChars` 且与观测不同 → 画 `X!读到→期望`（这是「人审」最想看的一格）
 *
 * ⚠️ 标签不能为空串 否则画板不会画文字（引擎侧：空标签 = 纯框）；所以空格的标签是单个空格。
 */
function cellBoxes(grid, chars, options) {
    const cfg = options || {};
    const size = cfg.cellBoxSize == null ? DEFAULTS.cellBoxSize : cfg.cellBoxSize;
    const expect = cfg.expectChars || null;
    const scores = cfg.scores || null;
    const conflicts = cfg.conflicts || null;
    const drawEmpty = cfg.drawEmptyCells === true;
    const items = [];
    if (!grid || !chars) {
        return items;
    }
    for (let row = 0; row < 10; row++) {
        for (let col = 0; col < 9; col++) {
            const index = row * 9 + col;
            const got = chars[index];
            const empty = got == null || got === "";
            if (empty && !drawEmpty) {
                // 空格的例外：期望该有子却读成空 —— 这才是漏检，必须画出来
                const want = expect ? expect[index] : null;
                if (!want) {
                    continue;
                }
            }
            const center = centerOf(grid, row, col);
            let label = empty ? " " : String(got);
            if (conflicts && conflicts[index]) {
                label = "?!" + label;
            }
            if (expect) {
                const want = expect[index] == null ? "" : String(expect[index]);
                if (want !== (empty ? "" : String(got))) {
                    label = "X!" + (empty ? "空" : String(got)) + "→" + (want || "空");
                }
            }
            const score = (scores && isFinite(scores[index])) ? Number(scores[index]) : 0;
            items.push({
                x1: center.x - size / 2,
                y1: center.y - size / 2,
                x2: center.x + size / 2,
                y2: center.y + size / 2,
                label: label,
                score: score
            });
        }
    }
    return items;
}

/**
 * 原始检测框（模型直接输出的那些）→ 框 + `标签 分数`。
 * 用来判断「漏检 / 同一子检测两次 / 框偏到格子外」。
 */
function detectionBoxes(detections, options) {
    const cfg = options || {};
    const fallback = cfg.defaultBoxSize == null ? DEFAULTS.defaultBoxSize : cfg.defaultBoxSize;
    const max = cfg.maxDetections == null ? DEFAULTS.maxDetections : cfg.maxDetections;
    const items = [];
    const list = detections || [];
    for (let i = 0; i < list.length && items.length < max; i++) {
        const det = list[i];
        if (!det || !isFinite(det.x) || !isFinite(det.y)) {
            continue;
        }
        const width = isFinite(det.width) && det.width > 0 ? det.width : fallback;
        const height = isFinite(det.height) && det.height > 0 ? det.height : fallback;
        const score = det.score == null ? 1 : Number(det.score);
        items.push({
            x1: det.x - width / 2,
            y1: det.y - height / 2,
            x2: det.x + width / 2,
            y2: det.y + height / 2,
            label: String(det.label == null ? "?" : det.label) + " " + Math.round(score * 100) / 100,
            score: score
        });
    }
    return items;
}

/** 本手计划（票据的起点/落点）→ 两个框，标签带箭头，方便核对「点的地方对不对」。 */
function planBoxes(grid, ticket, options) {
    const cfg = options || {};
    const size = cfg.planBoxSize == null ? 40 : cfg.planBoxSize;
    const items = [];
    const iccsText = ticket && ticket.moveICCS ? String(ticket.moveICCS) : "";
    if (!grid || !/^[a-i][0-9][a-i][0-9]$/.test(iccsText)) {
        return items;
    }
    const place = function (offset, prefix) {
        const col = "abcdefghi".indexOf(iccsText.charAt(offset));
        const row = 9 - Number(iccsText.charAt(offset + 1));
        const center = centerOf(grid, row, col);
        items.push({
            x1: center.x - size / 2,
            y1: center.y - size / 2,
            x2: center.x + size / 2,
            y2: center.y + size / 2,
            label: prefix + iccsText.substring(offset, offset + 2),
            score: 1
        });
    };
    place(0, "▶");
    place(2, "◀");
    return items;
}

/**
 * 屏幕上一个点 → 小方框（用来标「实际点在哪里」：点击落点/鼠标位）。
 * 人审点击准确度时最有用：框应该正好套住棋子圆心。
 */
function pointBoxes(points, options) {
    const cfg = options || {};
    const size = cfg.pointBoxSize == null ? 34 : cfg.pointBoxSize;
    const items = [];
    const list = points || [];
    for (let i = 0; i < list.length; i++) {
        const point = list[i];
        if (!point || !isFinite(point.x) || !isFinite(point.y)) {
            continue;
        }
        items.push({
            x1: point.x - size / 2,
            y1: point.y - size / 2,
            x2: point.x + size / 2,
            y2: point.y + size / 2,
            label: String(point.label == null ? "" : point.label),
            score: 1
        });
    }
    return items;
}

/**
 * HUD 那行文字：画的是什么模式 + 关键数字 + 结论。 */
function summary(input) {
    const result = input && input.result;
    const parts = [];
    parts.push("绘制 " + (input && input.mode ? input.mode : "cells"));
    if (result) {
        parts.push("检测 " + (result.detectionCount == null ? "?" : result.detectionCount));
        parts.push("未知 " + (result.unknownCells == null ? "?" : result.unknownCells));
        if (result.timing && result.timing.totalMs != null) {
            parts.push("本帧 " + Math.round(result.timing.totalMs) + "ms");
        }
        if (result.stats && result.stats.autoAlign) {
            parts.push("微调 " + result.stats.autoAlign.unknown);
        }
        if (result.repaired && result.repaired.demoted && result.repaired.demoted.length) {
            parts.push("降级 " + result.repaired.demoted.length);
        }
    }
    if (input && input.grid && isFinite(input.grid.originX)) {
        parts.push("原点 (" + Math.round(input.grid.originX) + "," + Math.round(input.grid.originY) +
            ") 格距 (" + Math.round(input.grid.stepX) + "," + Math.round(input.grid.stepY) + ")");
    }
    if (input && input.extraText) {
        parts.push(String(input.extraText));
    }
    return parts.join("｜");
}

/**
 * 汇总成一帧画板内容。
 *
 * @param input.grid       已校准几何（必须）
 * @param input.result     recognizer 的结果（detections / chars / unknownCells / timing）
 * @param input.expectChars 期望盘面（可选：验证阶段传票据的预期后局面，能把「读错」标成 X!）
 * @param input.ticket     本手票据（可选：画起点/落点）
 * @param input.mode       "grid"（只网格）/ "cells"（网格+每格字）/ "full"（再加原始检测框）
 * @param input.extraText  HUD 追加文字（轮次/结论等）
 * @returns {items, text}
 */
function build(input) {
    const opts = input || {};
    const cfg = opts.config || {};
    const mode = opts.mode === "grid" || opts.mode === "full" ? opts.mode : "cells";
    let items = gridBoxes(opts.grid, cfg);
    if (mode !== "grid") {
        const assigned = opts.result && opts.result.assigned ? opts.result.assigned : null;
        const scores = assigned ? assigned.map(function (cell) { return cell ? cell.score : 0; }) : null;
        const conflicts = assigned ? assigned.map(function (cell) { return !!(cell && cell.conflict); }) : null;
        items = items.concat(cellBoxes(opts.grid, opts.result ? opts.result.chars : null, {
            cellBoxSize: cfg.cellBoxSize,
            expectChars: opts.expectChars,
            scores: scores,
            conflicts: conflicts,
            drawEmptyCells: mode === "full" && cfg.drawEmptyCells === true
        }));
        items = items.concat(planBoxes(opts.grid, opts.ticket, cfg));
    }
    if (mode === "full" && opts.result) {
        items = items.concat(detectionBoxes(opts.result.detections, cfg));
    }
    if (mode !== "grid" && opts.points) {
        items = items.concat(pointBoxes(opts.points, cfg));
    }
    return { items: items, text: summary(opts) };
}

module.exports = {
    DEFAULTS: DEFAULTS,
    gridBoxes: gridBoxes,
    cellBoxes: cellBoxes,
    detectionBoxes: detectionBoxes,
    planBoxes: planBoxes,
    pointBoxes: pointBoxes,
    centerOf: centerOf,
    summary: summary,
    build: build
};
