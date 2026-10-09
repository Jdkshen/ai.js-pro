// @engine quickjs
"use strict";

// A plain, pixel-free snapshot shared by the chess HUD and native renderer.
// It is display data only: neither recognition nor search reads it back.
const ICCS = /^[a-i][0-9][a-i][0-9]$/;
const MOVE_STATES = ["PRECHECK", "CLICKING", "VERIFYING"];
const fen = require("../chess/fen.js");
const geometry = require("../vision/geometry.js");
const legal = require("../chess/legal-moves.js");

function observedLayout(result) {
    if (!result) return "";
    if (Array.isArray(result.chars)) try {
        return fen.layoutOf(result.chars.map(function(ch) {return ch === "." ? null : ch;}));
    } catch (error) {return "";}
    return String(result.layout || "").split(/\s/)[0];
}

/** 已发送的走法可在确认/等对方时保持显示；仅用于显示，不延长点击票据有效期。 */
function displayTicket(input) {
    if (validTicket(input)) return input.ticket;
    const t = input.retainedTicket, v = input.versions, r = input.result, g = input.grid;
    if (!t || !v || !g || !r || input.running === false || input.captureInvalid ||
        r.ok === false || r.unknownCells > 0 || ["VERIFYING","WAIT_STABLE","WAIT_TURN","THINKING","PRECHECK","CLICKING"].indexOf(input.state) < 0 ||
        t.executionState === "CANCELLED" || t.sessionId !== v.sessionId || t.modelVersion !== v.modelVersion ||
        t.geometryVersion !== v.geometryVersion || t.geometryVersion !== g.version ||
        v.positionVersion < t.basePositionVersion ||
        v.positionVersion > (t.displayPositionVersion || t.basePositionVersion) + 3) return null;
    return observedLayout(r) === String(t.expectedAfterFen).split(/\s/)[0] ? t : null;
}

function arrow(grid, move, color) {
    const from = geometry.pointAt(grid, move.charCodeAt(0) - 97, 9 - Number(move.charAt(1)));
    const to = geometry.pointAt(grid, move.charCodeAt(2) - 97, 9 - Number(move.charAt(3)));
    const unit = Math.min(Math.abs(grid.stepX), Math.abs(grid.stepY));
    return {kind:"arrow",x1:from.x,y1:from.y,x2:to.x,y2:to.y,
        color:color,strokeWidth:Math.max(3,unit * 0.045),headSize:unit * 0.22};
}

/** 多路预判上限与逐路配色：第 1 路不透明，越靠后越淡（宿主 Color.parseColor 支持 #AARRGGBB）。 */
const PREDICTION_LIMIT = 5;
const PREDICTION_COLORS = ["#40C4FF", "#B840C4FF", "#9640C4FF", "#7A40C4FF", "#6440C4FF"];

/**
 * 对面应手 top-K（2026-10-05 用户要求「给对面多算了，几路绘制出来」）：
 * 逐条按「同一张票、同一局面里真合法」过滤（非法/重复去掉），顺序保持引擎排名（第 1 路最可能）。
 * 一条都没有时退回主变第 2 手（旧行为）。
 */
function predictedReplies(ticket, prediction) {
    let legalSet = null;
    try {
        const after = fen.parseFen(ticket.expectedAfterFen);
        legalSet = {};
        // ★ 2026-10-06 修复：parseFen 的字段是 sideToMove（此前误用 after.side=undefined，
        //   会恒按黑方过滤——对手是红方时 5 路全被误杀，只剩单路回退）。
        legal.allLegalMoves(after.board, after.sideToMove).forEach(function (move) { legalSet[move.iccs] = true; });
    } catch (error) { legalSet = null; }
    const out = [];
    const push = function (value) {
        const iccs = String(value == null ? "" : value);
        if (legalSet && legalSet[iccs] === true && out.indexOf(iccs) < 0 && out.length < PREDICTION_LIMIT) out.push(iccs);
    };
    const list = prediction && prediction.key === ticket.expectedAfterFen && Array.isArray(prediction.list) ? prediction.list : [];
    list.forEach(push);
    const pv = ticket.pvIccs || [];
    if (out.length === 0 && pv[0] === ticket.moveICCS) push(pv[1]);
    return out;
}

/** 预判箭头的排名小标签（敌1/敌2…）：摆在箭头中点旁，共起点的多路也不会叠在一起。 */
function predictionLabel(item, rank, unit) {
    const dx = item.x2 - item.x1, dy = item.y2 - item.y1;
    const length = Math.sqrt(dx * dx + dy * dy);
    if (!(length > unit * 0.3)) return null;
    const ux = dx / length, uy = dy / length;
    const size = Math.max(18, unit * 0.26);
    return { kind:"text",
        x1:(item.x1 + item.x2) / 2 - uy * unit * 0.16 - size,
        y1:(item.y1 + item.y2) / 2 + ux * unit * 0.16 - size * 0.5,
        label:"敌" + (rank + 1), color:item.color, size:size };
}

function number(value) {
    return typeof value === "number" && isFinite(value) ? value : null;
}

function validTicket(input) {
    const ticket = input.ticket, grid = input.grid, versions = input.versions, result = input.result;
    if (!ticket || !grid || !versions || !result || result.ok === false || result.unknownCells > 0 ||
        input.running === false || input.captureInvalid || !ICCS.test(String(ticket.moveICCS)) ||
        MOVE_STATES.indexOf(input.state) < 0 ||
        ticket.executionState === "CONFIRMED" || ticket.executionState === "CANCELLED") return false;
    if (number(input.nowMono) == null || number(ticket.expiresAtMono) == null ||
        input.nowMono >= ticket.expiresAtMono || input.nowMono < ticket.createdAtMono) return false;
    if (ticket.sessionId !== versions.sessionId || ticket.basePositionVersion !== versions.positionVersion ||
        ticket.geometryVersion !== versions.geometryVersion || ticket.geometryVersion !== grid.version ||
        ticket.modelVersion !== versions.modelVersion) return false;
    // The most recent observed board must still be the board which produced this move.
    // An own move or opponent reply invalidates the old arrow even before the next search.
    let layout = result.layout || "";
    // The glyph path supplies cells without a layout string; repaired cells also
    // take precedence over an older cached layout. Do not retain native frame pixels.
    if (Array.isArray(result.chars)) try {
        layout = fen.layoutOf(result.chars.map(function (ch) { return ch === "." ? null : ch; }));
    } catch (error) { return false; }
    if (!layout || String(layout).split(/\s/)[0] !== String(ticket.baseFen).split(/\s/)[0]) return false;
    return true;
}

function timings(detail) {
    const sections = detail && detail.smart && detail.smart.sections || [];
    const out = { captureMs: null, grabMs: null, geometryMs: null, stableMs: null, recognitionMs: null, totalMs: null };
    sections.forEach(function (section) {
        const ms = number(section.ms);
        if (ms == null || ms < 0) return;
        const name = String(section.name);
        if (name.indexOf("抓帧") === 0) out.grabMs = ms;
        else if (name.indexOf("棋盘几何") === 0) out.geometryMs = ms;
        else if (name.indexOf("稳定帧") === 0) out.stableMs = ms;
        else if (name === "字形识别" || name === "流水线") out.recognitionMs = ms;
    });
    out.totalMs = number(detail && detail.smart && detail.smart.total);
    return out;
}

function build(input) {
    const opts = input || {}, grid = opts.grid, ticket = displayTicket(opts);
    const timing = timings(opts.timing);
    const hasMove = !!ticket;
    const capture = opts.capture || {};
    timing.captureMs = number(capture.captureMs);
    const snapshot = {
        frameId: number(capture.frameId), timestamp: number(capture.timestamp),
        windowId: number(capture.windowId), packageName: capture.packageName || "",
        geometryVersion: grid ? grid.version : null,
        positionVersion: opts.versions ? opts.versions.positionVersion : null,
        state: opts.state || "IDLE", hasMove: hasMove, bestMove: hasMove ? ticket.moveICCS : "",
        engine: hasMove ? { source:ticket.source, depth:number(ticket.depth), score:number(ticket.score),
            ms:number(ticket.engineMs), pvIccs:[] } : null,
        timing: timing, items: [], text: ""
    };
    const lines = ["状态 " + (opts.stateLabel || snapshot.state) +
        "｜检测 " + (opts.result && opts.result.detectionCount != null ? opts.result.detectionCount : "—") +
        "｜未知 " + (opts.result && opts.result.unknownCells != null ? opts.result.unknownCells : "—")];
    const timeParts = [];
    [["截图", "captureMs"], ["抓帧", "grabMs"], ["几何", "geometryMs"], ["帧检查", "stableMs"],
        ["识别", "recognitionMs"], ["本帧", "totalMs"]].forEach(function (pair) {
        if (timing[pair[1]] != null) timeParts.push(pair[0] + " " + Math.round(timing[pair[1]]) + "ms");
    });
    if (timeParts.length) lines.push(timeParts.join("｜"));
    if (snapshot.frameId != null) lines.push("窗口帧 " + snapshot.frameId + "｜几何 v" + snapshot.geometryVersion);
    if (hasMove) {
        const move = ticket.moveICCS;
        const center = function (offset) {
            return geometry.pointAt(grid, move.charCodeAt(offset) - 97, 9 - Number(move.charAt(offset + 1)));
        };
        const screenSquare = function (offset) {
            let col = move.charCodeAt(offset) - 97, row = 9 - Number(move.charAt(offset + 1));
            if (grid.orientation === geometry.ORIENTATION_RED_TOP) { col = 8 - col; row = 9 - row; }
            if (grid.stepX < 0) col = 8 - col;
            if (grid.stepY < 0) row = 9 - row;
            return {col:col, row:row};
        };
        snapshot.from = center(0); snapshot.to = center(2);
        snapshot.fromSquare = screenSquare(0);
        snapshot.toSquare = screenSquare(2);
        const unit = Math.min(Math.abs(grid.stepX), Math.abs(grid.stepY));
        if (![snapshot.from.x, snapshot.from.y, snapshot.to.x, snapshot.to.y, unit].every(function (v) {
            return number(v) != null;
        }) || unit < 1) {
            snapshot.hasMove = false; snapshot.bestMove = ""; snapshot.engine = null;
            return Object.assign(snapshot, { text:lines.join("\n") });
        }
        snapshot.items = [arrow(grid, move, "#FFD54F")];
        snapshot.opponentMoves = predictedReplies(ticket, opts.prediction);
        snapshot.opponentMove = snapshot.opponentMoves.length ? snapshot.opponentMoves[0] : "";
        snapshot.opponentMoves.forEach(function (reply, rank) {
            const item = arrow(grid, reply, PREDICTION_COLORS[Math.min(rank, PREDICTION_COLORS.length - 1)]);
            if (rank > 0) item.strokeWidth = Math.max(2, item.strokeWidth * (1 - rank * 0.12));
            snapshot.items.push(item);
            const label = predictionLabel(item, rank, unit);
            if (label) snapshot.items.push(label);
        });
        const engine = snapshot.engine;
        engine.pvIccs = (ticket.pvIccs || []).filter(function (item) { return ICCS.test(item); }).slice(0, 6);
        if (engine.pvIccs[0] !== move) engine.pvIccs = [move];
        lines.unshift((engine.source === "book" ? "开局库" : engine.source || "引擎") + " " + move +
            (engine.depth == null ? "" : "｜深度 " + engine.depth) +
            (engine.score == null ? "" : "｜评分 " + Math.round(engine.score) + "cp") +
            (engine.ms == null ? "" : "｜算棋 " + Math.round(engine.ms) + "ms"));
        lines.push("PV " + engine.pvIccs.join(" "));
        lines.push("起点 第" + (snapshot.fromSquare.col + 1) + "列" + (snapshot.fromSquare.row + 1) +
            "行 → 目标 第" + (snapshot.toSquare.col + 1) + "列" + (snapshot.toSquare.row + 1) + "行");
    }
    snapshot.text = lines.join("\n");
    return snapshot;
}

// Full-width text only enters a free vertical band. Conservative exclusion also
// covers the chess pieces outside the outer grid lines and bounded touch windows.
function hudBounds(screen, band, obstacles) {
    if (!screen || number(screen.width) == null || number(screen.height) == null ||
        !band || number(band.top) == null || number(band.bottom) == null) return null;
    const top = Math.max(0, band.top), bottom = Math.min(screen.height, band.bottom);
    const blocks = (obstacles || []).filter(function (box) {
        return box && [box.y, box.height].every(function (v) { return number(v) != null; }) && box.height > 0;
    }).map(function (box) { return {top:Math.max(top, box.y - 8), bottom:Math.min(bottom, box.y + box.height + 8)}; })
        .filter(function (box) { return box.bottom > box.top; }).sort(function (a, b) { return a.top - b.top; });
    let cursor = top, best = null;
    const consider = function (end) {
        if (end - cursor >= 96 && (!best || end - cursor > best.bottom - best.top)) best = {top:cursor, bottom:end};
    };
    blocks.forEach(function (box) { consider(box.top); cursor = Math.max(cursor, box.bottom); });
    consider(bottom);
    return best && screen.width > 48 ? {left:12, top:best.top + 8, right:screen.width - 12, bottom:best.bottom - 8} : null;
}

module.exports = { build:build, validTicket:validTicket, displayTicket:displayTicket, observedLayout:observedLayout,
    timings:timings, hudBounds:hudBounds };
