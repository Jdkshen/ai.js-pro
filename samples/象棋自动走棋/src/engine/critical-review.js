// @engine quickjs
"use strict";

// One bounded second search. Scores from UCI are relative to the mover;
// previousScore in the driver is relative to red. Never mix the two.
function plan(board, side, chosen, raw, options) {
    const opts = options || {};
    if (opts.criticalReview !== true || Number(opts.depth) > 0 || !chosen) return null;
    const base = Math.max(100, Number(opts.movetime) || 1200);
    const totalLimit = Number(opts.maxTotalMs);
    const spent = Math.max(base, Number(opts.elapsedMs) || 0);
    // ★ 2026-10-06（用户「关键步加时」）：上限 3000→6500ms —— 关键步总预算 ≈ 首搜 5s + 复核 6.5s ≈ 11~12s；
    //   总上限仍是 15s（movetime 较大时自动被剩余预算夹住，小预算时不超过首搜 2 倍，行为可控）。
    const extra = Math.min(base * 2, 6500, Math.max(0, (isFinite(totalLimit) && totalLimit > 0 ? totalLimit : 15000) - spent));
    if (extra < 100) return null;
    const reasons = [];
    const previous = opts.previousScore;
    if (typeof previous === "number" && isFinite(previous) && chosen.scoreCp != null &&
        isFinite(Number(chosen.scoreCp)) && Math.abs(previous) < 29000 &&
        previous * (side === "b" ? -1 : 1) - Number(chosen.scoreCp) >= 120) reasons.push("评分下降");
    const byDepth = {};
    String(raw || "").split(/\r?\n/).forEach(function (line) {
        const depth = /\bdepth (\d+)/.exec(line), pv = /\bpv ([a-i][0-9][a-i][0-9])/.exec(line);
        const mpv = /\bmultipv (\d+)/.exec(line);
        if (depth && Number(depth[1]) >= 8 && pv && (!mpv || mpv[1] === "1")) byDepth[depth[1]] = pv[1];
    });
    const trace = Object.keys(byDepth).map(Number).sort(function (a,b) {return a-b;}).slice(-3);
    if (trace.length === 3 && byDepth[trace[0]] !== byDepth[trace[1]] &&
        byDepth[trace[1]] !== byDepth[trace[2]]) reasons.push("主着法反复变化");
    const move = String(chosen.move || "");
    if (/^[a-i][0-9][a-i][0-9]$/.test(move) && board) {
        const target = (9 - Number(move[3])) * 9 + move.charCodeAt(2) - 97;
        if (/^[rnc]$/i.test(board[target] || "")) reasons.push("车马炮交换");
    }
    if (chosen.mate != null) reasons.push("杀棋复核");
    return reasons.length ? { extraMs: extra, reason: reasons.join("、") } : null;
}
module.exports = { plan: plan };
