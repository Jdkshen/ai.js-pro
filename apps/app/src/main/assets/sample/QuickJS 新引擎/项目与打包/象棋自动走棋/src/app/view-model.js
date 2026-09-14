// @engine quickjs
/**
 * 视图模型（§14：原始状态 → 页面/悬浮窗显示数据）。
 *
 * 界面只吃这里的输出：不再自己拼状态文案、不再自己判断"该显示绿还是黄"。
 * 纯函数式、不碰宿主（可单测）。
 */
"use strict";

const labels = require("../ui/labels.js");

/** 状态行：模式 / 状态机 / 轮次 / 未知格 / 前台 / 锁 / 通道。 */
function statusLine(state) {
    const pieces = [];
    pieces.push(labels.MODE_LABEL[state.mode] || labels.MODE_LABEL.watch);
    const machine = labels.stateText(state.machineState);
    pieces.push(machine + (state.machineReason ? "：" + state.machineReason : ""));
    if (state.turn === "w") {
        pieces.push(labels.TEXT.turnMine);
    } else if (state.turn === "b") {
        pieces.push(labels.TEXT.turnTheirs);
    } else if (state.turn === null) {
        pieces.push(labels.TEXT.turnUnknown);
    }
    if (state.unknownCells != null) {
        pieces.push(labels.TEXT.unknownCells(state.unknownCells));
    }
    if (state.focusPackage) {
        pieces.push(state.focusIsGame === false ? labels.TEXT.focusGameBad : labels.TEXT.focusGameOk);
    }
    pieces.push(state.lockHeld ? labels.TEXT.lockHeld : labels.TEXT.lockFree);
    if (state.captureChannel) {
        pieces.push("截图 " + state.captureChannel);
    }
    pieces.push("点击 " + (labels.CHANNEL_LABEL[state.channel] || state.channel));
    return pieces.join("｜");
}

/** 状态行的颜色建议（界面据此上色，不自己判断）。 */
function statusTone(state) {
    if (state.machineState === "ERROR") {
        return "bad";
    }
    if (state.machineState === "PAUSED" || state.machineState === "RECOVERING") {
        return "warn";
    }
    if (state.focusIsGame === false) {
        return "warn";
    }
    if (state.running && state.turn === "w") {
        return "ok";
    }
    return "text";
}

/** 第二行：这一手在做什么（算棋/点击计划/建议）。 */
function stepLines(state) {
    const lines = [];
    if (state.lastSuggest) {
        lines.push("建议 " + state.lastSuggest.iccs +
            (state.lastSuggest.score != null ? "（评分 " + state.lastSuggest.score + "）" : ""));
    }
    if (state.lastClickPlan) {
        lines.push("本手 " + state.lastClickPlan.iccs +
            " 格(" + state.lastClickPlan.fromCol + "," + state.lastClickPlan.fromRow + ")→(" +
            state.lastClickPlan.toCol + "," + state.lastClickPlan.toRow + ")");
    }
    if (state.budgetNote) {
        lines.push(state.budgetNote);
    }
    if (state.calibrationNote) {
        lines.push(state.calibrationNote);
    }
    return lines;
}

/** 统计行（只读展示，逗号分隔的键值）。 */
function statsLine(state) {
    const stats = state.stats || {};
    const wanted = [
        ["observations", "观察"], ["clicks", "点击"], ["thinkMs", "算棋ms"],
        ["unknownTurn", "轮次未知"], ["rawCorrected", "原始修正"],
        ["sideFixes", "盘面改正"], ["sourceGoneRefusals", "起点已走"],
        ["budgetPauses", "预算暂停"], ["errors", "错误"]
    ];
    const pieces = [];
    for (let i = 0; i < wanted.length; i++) {
        const value = stats[wanted[i][0]];
        if (value != null && value !== 0) {
            pieces.push(wanted[i][1] + " " + value);
        }
    }
    return pieces.join("，");
}

/** 日志尾部若干行（截断规则与面板一致）。 */
function logLines(list, count, truncate) {
    const all = list || [];
    const n = count == null ? 2 : count;
    const limit = truncate == null ? 42 : truncate;
    const tail = all.slice(-n);
    return tail.map(function (entry) {
        const text = typeof entry === "string" ? entry : (entry && entry.text) || "";
        return text.length > limit ? (text.slice(0, limit - 1) + "…") : text;
    });
}

module.exports = {
    statusLine: statusLine,
    statusTone: statusTone,
    stepLines: stepLines,
    statsLine: statsLine,
    logLines: logLines
};
