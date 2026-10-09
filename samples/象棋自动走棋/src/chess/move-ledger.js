// @engine quickjs
/**
 * VerifiedMoveLedger（已校验着法账本）—— **只收「已验证」的着法，不把猜测写进历史**。
 *
 * 任务书 §4「棋谱账本和启动中局」的落地：
 *   · 一局 = 一个基准段：`begin(initialFen)`（从标准开局确认）或 `setPartial(当前 fen)`（中途接管）；
 *   · `confirm()` 只接受「棋规合法 + 落子后与验证值逐格一致」的着法；被拒的**不入历史**；
 *   · `positionCommand()` 输出 `position fen <baseFen> moves <m1> ...`；partial 段的 baseFen 是
 *     **确认过的**局面 FEN（中途接管时允许「以当前 FEN 搜索」），但 `state().historyStatus`
 *     必须让调用方看得见「历史不完整」，绝不把没验证过的先前着法塞进命令；
 *   · `downgrade()`：基准被按观测原地改正（对账修正/OCR 改正）时降级为 partial —— 保留已校验的
 *     着法序列，但明确标记「这一段可能不完整」；调用方决定是否只给当前 FEN。
 *
 * 纯模块：只依赖棋规纯函数（rules.js / legal-moves.js），不碰识别、引擎与 UI。
 */
"use strict";

const legal = require("./legal-moves.js");
const rules = require("./rules.js");

const HISTORY = {
    COMPLETE: "complete",
    PARTIAL: "partial",
    UNKNOWN: "unknown"
};

const ICCS_RE = /^[a-i][0-9][a-i][0-9]$/;

/**
 * 盘面的「单位安全」序列化（null → "."）——**比较盘面必须用它，不能用 join("")**：
 * join 会折叠掉 null，`a0a1`（车从底线上一格）这类着法的前后两盘拼出来是**同一串**，
 * 连续性/镜像检查会全部误判通过（2026-09-23 回放用例 `driver-replay-turns` 抳到）。
 */
function cellsKey(board) {
    if (!board || !board.length) {
        return "";
    }
    let out = "";
    for (let i = 0; i < board.length; i++) {
        const ch = board[i];
        out += (ch == null ? "." : String(ch));
    }
    return out;
}

/**
 * @param options { note? }
 */
function createMoveLedger(options) {
    const opts = options || {};
    const note = typeof opts.note === "function" ? opts.note : function () { };

    /** 段的基准 FEN（半途可能是「接管时确认过的局面」） */
    let baseFen = null;
    let historyStatus = HISTORY.UNKNOWN;
    /** [{ iccs, side, source, atMono, evidence }] —— 只可能由 confirm() 追加 */
    const moves = [];

    function state() {
        return {
            baseFen: baseFen,
            historyStatus: historyStatus,
            ply: moves.length,
            moves: moves.map(function (item) { return item.iccs; }),
            last: moves.length ? Object.assign({}, moves[moves.length - 1]) : null
        };
    }

    /** 从确认过的完整局面开新段（标准开局）。 */
    function begin(fen, status) {
        baseFen = fen || null;
        historyStatus = String(status || (baseFen ? HISTORY.COMPLETE : HISTORY.UNKNOWN));
        moves.length = 0;
        return state();
    }

    /** 中途接管：以「当前确认过的局面」为段的基准；先前着法一律不伪写。 */
    function setPartial(fen, reason) {
        baseFen = fen || null;
        historyStatus = baseFen ? HISTORY.PARTIAL : HISTORY.UNKNOWN;
        moves.length = 0;
        note("历史不完整（" + (reason || "中途接管") + "）：以当前局面为段基准，不伪写先前着法");
        return state();
    }

    /** 基准被原地改正（无着法语义）：保留已校验序列，但把完整降级为 partial。 */
    function downgrade(reason) {
        if (historyStatus === HISTORY.COMPLETE) {
            historyStatus = HISTORY.PARTIAL;
            note("账本降级为「历史不完整」：" + (reason || "基准被原地改正"));
        }
        return state();
    }

    /** 作废（新会话/换边等：整链不再可信）。 */
    function clear(reason) {
        baseFen = null;
        historyStatus = HISTORY.UNKNOWN;
        moves.length = 0;
        if (reason) {
            note("账本作废：" + reason);
        }
        return state();
    }

    /**
     * 收一手**已验证**的着法。
     * @param entry {
     *   iccs, fromBoard, side,            // 必填：着法与轮走方 + 着法之前的盘面
     *   afterBoard?,                      // 可选：验证后的盘面（给了就逐格核验，不一致拒收）
     *   toleranceIndexes?,                // 可选：允许不一致的格下标（如装饰格补丁）
     *   source, atMono?, evidence?        // 出处："our-click" | "opponent-observed" | "manual-confirmed"
     * }
     * @returns { ok, ply?, appliedBoard?, reason?, diffIndexes? }
     */
    function confirm(entry) {
        const e = entry || {};
        const iccs = String(e.iccs == null ? "" : e.iccs);
        if (!ICCS_RE.test(iccs)) {
            return { ok: false, reason: "着法记法不合法（ICCS）：" + (iccs || "空") };
        }
        const fromBoard = e.fromBoard;
        if (!fromBoard || !fromBoard.length) {
            return { ok: false, reason: "没有起点盘面，无法核验：" + iccs };
        }
        const from = rules.iccsSquare(iccs, 0);
        const to = rules.iccsSquare(iccs, 1);
        if (from == null || to == null) {
            return { ok: false, reason: "ICCS 解析失败：" + iccs };
        }
        const cols = rules.COLS;
        const check = legal.isLegalMove(fromBoard,
            from % cols, Math.floor(from / cols), to % cols, Math.floor(to / cols), e.side);
        if (!check || check.legal !== true) {
            return { ok: false, reason: "棋规不合法：" + iccs + "（" + ((check && check.reason) || "未知") + "）" };
        }
        const applied = legal.applyMove(fromBoard, { iccs: iccs });
        if (!applied || !applied.board) {
            return { ok: false, reason: "应用着法失败（起点已无子？）：" + iccs };
        }
        if (e.afterBoard && e.afterBoard.length) {
            const tolerance = e.toleranceIndexes || [];
            const diffs = legal.diffBoards(applied.board, e.afterBoard).filter(function (item) {
                return tolerance.indexOf(item.index) < 0;
            });
            if (diffs.length > 0) {
                return {
                    ok: false,
                    reason: "落子后盘面与验证值不一致（" + diffs.length + " 格）",
                    diffIndexes: diffs.map(function (item) { return item.index; })
                };
            }
        }
        const beforeKey = cellsKey(fromBoard);
        // 幂等：同一手（同 iccs + 同起点盘面）的**重复确认**不重复记账
        //（驱动对一张票据会连续两次走完确认块——第二次是同一件事，不是新手）
        if (moves.length > 0) {
            const last = moves[moves.length - 1];
            if (last.iccs === iccs && last.beforeKey === beforeKey) {
                return { ok: true, duplicate: true, ply: moves.length, appliedBoard: applied.board };
            }
        }
        moves.push({
            iccs: iccs,
            side: e.side || null,
            source: e.source || "unknown",
            atMono: e.atMono == null ? null : e.atMono,
            evidence: e.evidence || null,
            beforeKey: beforeKey
        });
        return { ok: true, ply: moves.length, appliedBoard: applied.board };
    }

    /**
     * 引擎命令：只输出「基准 + 已验证着法」。
     * 没有基准（unknown）时返回 null —— 调用方自己退回「只给当前 FEN」。
     */
    function positionCommand() {
        if (!baseFen || historyStatus === HISTORY.UNKNOWN) {
            return null;
        }
        const head = "position fen " + baseFen;
        if (moves.length === 0) {
            return head;
        }
        return head + " moves " + moves.map(function (item) { return item.iccs; }).join(" ");
    }

    /** 与 auto-driver 的「局面键」同一口径（布局 + 轮走方），供外层检测用。 */
    function positionKey(board, side) {
        return (board ? board.join("") : "") + "|" + (side || "");
    }

    return {
        HISTORY: HISTORY,
        begin: begin,
        setPartial: setPartial,
        downgrade: downgrade,
        clear: clear,
        confirm: confirm,
        positionCommand: positionCommand,
        positionKey: positionKey,
        state: state
    };
}

module.exports = { createMoveLedger: createMoveLedger, HISTORY: HISTORY, cellsKey: cellsKey };
