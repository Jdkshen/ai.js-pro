// @engine quickjs
/** 求胜的局面账本与软偏好：保留引擎最优着法，只在同局面的可靠评分接近时换招。 */
"use strict";

const legal = require("../chess/legal-moves.js");

function opposite(side) {
    return side === "b" ? "w" : "b";
}

/**
 * 局面指纹：「90 格逐格序列 | 该走棋方」。
 * ⚠️ **必须逐格拼（null → "."）**，不能用 `board.join("")`：join 会把空格折叠，
 *   稀疏残局里「子的先后顺序」不变时两个不同局面会算出同一个键（真机踩过，见 auto-driver 的同名注释）。
 */
function keyOf(board, side) {
    if (!board) {
        return "|" + (side || "");
    }
    let out = "";
    for (let i = 0; i < board.length; i++) {
        out += (board[i] == null ? "." : String(board[i]));
    }
    return out + "|" + (side || "");
}

/** 局面出现次数账本：每落定一手（或采纳一次新基准）就 note 一次。 */
function createSeenPositions() {
    let counts = Object.create(null);
    return {
        /** @returns 这个局面累计出现了几次（含这一次） */
        note: function (board, side) {
            const key = keyOf(board, side);
            counts[key] = (counts[key] || 0) + 1;
            return counts[key];
        },
        count: function (board, side) {
            return counts[keyOf(board, side)] || 0;
        },
        /** 记过多少种局面（诊断用） */
        kinds: function () {
            return Object.keys(counts).length;
        },
        clear: function () {
            counts = Object.create(null);
        }
    };
}

/**
 * 列出「走完之后会回到已经出现过的局面」的着法。
 *
 * @param moves 该走棋方的合法着法（[{iccs}]，legal.allLegalMoves 的产物）
 * @param board 当前局面（90 格数组）
 * @param side  该走棋方（"w"/"b"）
 * @param seen  createSeenPositions() 的账本
 * @param options { minSeen?（默认 1 = 第二次出现就避；2 = 只避第三次=判和那次），
 *                  limit?（默认 8：最多返回几手，别把候选全禁光） }
 * @returns [{ iccs, seen }]（没有就返回空数组）
 */
function repeatMoves(moves, board, side, seen, options) {
    const opts = options || {};
    const minSeen = opts.minSeen == null ? 1 : Math.max(1, Math.round(Number(opts.minSeen) || 1));
    const limit = opts.limit == null ? 8 : Math.max(1, Math.round(Number(opts.limit) || 8));
    const nextSide = opposite(side);
    const out = [];
    if (!moves || !board || !seen) {
        return out;
    }
    for (let i = 0; i < moves.length && out.length < limit; i++) {
        const move = moves[i];
        if (!move || !move.iccs) {
            continue;
        }
        let applied = null;
        try {
            applied = legal.applyMove(board, { iccs: move.iccs });
        } catch (error) {
            applied = null;
        }
        if (!applied || !applied.board) {
            continue;   // 走不了（那格空了/不合法）：交给引擎自己的合法性检查
        }
        const n = seen.count(applied.board, nextSide);
        if (n >= minSeen) {
            out.push({ iccs: move.iccs, seen: n });
        }
    }
    return out;
}

/** 只收已确认着法；任何吃子、基准修订或换局都终止此前的往返记录。 */
function createConfirmedShuttles() {
    let own = [];
    return {
        clear: function () { own = []; },
        note: function (before, side, iccs, mySide) {
            if (!before || before.length !== 90 || !/^[a-i][0-9][a-i][0-9]$/.test(iccs || "")) return;
            const from = (9 - Number(iccs[1])) * 9 + iccs.charCodeAt(0) - 97;
            const to = (9 - Number(iccs[3])) * 9 + iccs.charCodeAt(2) - 97;
            if (!before[from] || from === to) return;
            if (before[to]) own = []; // 对方吃子同样清空，避免已被吃子继续匹配旧路径
            if (side !== mySide) return;
            own.push({ from: from, to: to, piece: before[from], iccs: iccs, capture: !!before[to] });
            if (own.length > 3) own.shift();
        },
        moves: function (moves, board) {
            if (own.length !== 3 || !board) return [];
            const a = own[0], b = own[1], c = own[2];
            if (a.capture || b.capture || c.capture || !/^[RCNBA]$/i.test(a.piece || "") ||
                a.piece !== b.piece || a.piece !== c.piece ||
                a.from !== b.to || a.to !== b.from || a.from !== c.from || a.to !== c.to ||
                board[c.to] !== c.piece || board[c.from]) return [];
            return (moves || []).filter(function (move) {
                return move.fromRow * 9 + move.fromCol === c.to &&
                    move.toRow * 9 + move.toCol === c.from;
            }).map(function (move) { return { iccs: move.iccs, shuttle: true }; });
        }
    };
}

/** 子力价值（cp 量级）：只用来判「这算不算等价交换」，不参与评分。 */
const PIECE_VALUE = { R: 900, C: 450, N: 400, P: 100, A: 200, B: 200, K: 100000 };
/** 大子门槛：只把「车/马/炮」之间的交换当简化（兵/仕/相的交换不算 —— 残局里小兵反而是胜机）。 */
const SWAP_MIN_VALUE = 400;

function valueOfPiece(piece) {
    if (!piece) {
        return 0;
    }
    const table = PIECE_VALUE[String(piece).toUpperCase()];
    return table == null ? 0 : table;
}

/**
 * 列出「走出去会立刻被等价换掉（简化局面）」的吃子着法。
 *
 * 为什么（2026-09-25 用户：「为什么前面没有给他挖好坑，到后面又和老」）：
 * 引擎自己不会避兑子 —— 它假设对手永远不犯错，所以「均势 0 分 + 子力换光 = 0 分」，
 * 它眼里没有任何损失。但对我们来说：**子力换光 = 和棋**，而对手深度有限，
 * 留着子力的复杂局面才是它可能算错的地方。所以这一层替它「留子」。
 *
 * 判定口径（保守）：
 *   ① 只看**吃子**着法（主动送吃/对方来换不归我们管）；
 *   ② 吃的与被吃回的都得是大子（≥ 马，见 SWAP_MIN_VALUE）；
 *   ③ 两边价值要接近（|差| ≤ slack，默认 200）—— 「车吃马、被马吃回」这类不等价交换不动；
 *   ④ 必须真能**吃回同一格**（走完后的对方着法里有 to == 落点）—— 没人吃回 = 白赚，绝不排除。
 * 只生成偏好名单，不能当作硬排除表；是否换招由本次多路评分决定。
 *
 * @param moves 该走棋方的合法着法（legal.allLegalMoves 的产物，带 fromCol/fromRow/toCol/toRow）
 * @param board 当前局面（90 格数组，下标 = row * 9 + col）
 * @param side  该走棋方（"w"/"b"）
 * @param options { minValue?（默认 400 = 马）, slack?（默认 200）, limit?（默认 6） }
 * @returns [{ iccs, take, give }]（没有就返回空数组）
 */
function swapMoves(moves, board, side, options) {
    const opts = options || {};
    const minValue = opts.minValue == null ? SWAP_MIN_VALUE : Math.max(0, Number(opts.minValue) || 0);
    const slack = opts.slack == null ? 200 : Math.max(0, Number(opts.slack) || 0);
    const limit = opts.limit == null ? 6 : Math.max(1, Math.round(Number(opts.limit) || 6));
    const nextSide = opposite(side);
    const out = [];
    if (!moves || !board) {
        return out;
    }
    for (let i = 0; i < moves.length && out.length < limit; i++) {
        const move = moves[i];
        if (!move || !move.iccs) {
            continue;
        }
        const fromCol = move.fromCol, fromRow = move.fromRow, toCol = move.toCol, toRow = move.toRow;
        if (fromCol == null || fromRow == null || toCol == null || toRow == null) {
            continue;   // 只有 iccs 的着法不判（allLegalMoves 的产物一定带坐标）
        }
        const victim = board[toRow * 9 + toCol];
        if (!victim) {
            continue;   // 不是吃子着法
        }
        const takeValue = valueOfPiece(victim);
        if (takeValue < minValue) {
            continue;   // 吃的不是大子（兵/仕/相之间的交换不算「简化」）
        }
        const mineValue = valueOfPiece(board[fromRow * 9 + fromCol]);
        if (mineValue < minValue) {
            continue;   // 拿小兵去换大子不是「等价交换」（那是实惠，留着）
        }
        if (Math.abs(takeValue - mineValue) > slack) {
            continue;   // 不等价（赚了/亏了）⇒ 交给引擎自己判断
        }
        let applied = null;
        try {
            applied = legal.applyMove(board, move);
        } catch (error) {
            applied = null;
        }
        if (!applied || !applied.board) {
            continue;   // 走不了（起点空了等）：交给引擎自己的合法性检查
        }
        const replies = legal.allLegalMoves(applied.board, nextSide);
        let recapture = false;
        for (let k = 0; k < replies.length; k++) {
            if (replies[k].toCol === toCol && replies[k].toRow === toRow) {
                recapture = true;
                break;
            }
        }
        if (!recapture) {
            continue;   // 没人能吃回我落下的子 ⇒ 白赚，绝不排除
        }
        out.push({ iccs: move.iccs, take: takeValue, give: mineValue });
    }
    return out;
}

/** 最后一枚车马炮走到可等价兑回的位置，哪怕还有兵，也会大幅简化进攻子力。 */
function quietTradeMoves(moves, board, side, options) {
    const opts = options || {};
    const slack = opts.slack == null ? 200 : Math.max(0, Number(opts.slack) || 0);
    const limit = opts.limit == null ? 6 : Math.max(1, Math.round(Number(opts.limit) || 6));
    const nextSide = opposite(side);
    const out = [];
    if (!moves || !board) return out;
    let majors = 0;
    for (let i = 0; i < board.length; i++) {
        const piece = board[i];
        if (piece && ((side === "w" && piece === piece.toUpperCase()) ||
            (side === "b" && piece === piece.toLowerCase())) &&
            "RCN".indexOf(piece.toUpperCase()) >= 0) majors++;
    }
    if (majors !== 1) return out;
    for (let i = 0; i < moves.length && out.length < limit; i++) {
        const move = moves[i];
        if (!move || !move.iccs || move.fromCol == null || move.toCol == null) continue;
        const from = move.fromRow * 9 + move.fromCol;
        const to = move.toRow * 9 + move.toCol;
        const mine = board[from];
        const mineValue = valueOfPiece(mine);
        if (board[to] || mineValue < SWAP_MIN_VALUE) continue; // 静着；吃子交给 swapMoves
        const applied = legal.applyMove(board, move);
        if (!applied.board) continue;
        const replies = legal.allLegalMoves(applied.board, nextSide);
        for (let j = 0; j < replies.length; j++) {
            const reply = replies[j];
            if (reply.toCol !== move.toCol || reply.toRow !== move.toRow) continue;
            const enemy = applied.board[reply.fromRow * 9 + reply.fromCol];
            const enemyValue = valueOfPiece(enemy);
            if (enemyValue < SWAP_MIN_VALUE || Math.abs(enemyValue - mineValue) > slack) continue;
            const taken = legal.applyMove(applied.board, reply);
            if (!taken.board) continue;
            const recaptures = legal.allLegalMoves(taken.board, side);
            if (recaptures.some(function (recapture) {
                return recapture.toCol === move.toCol && recapture.toRow === move.toRow;
            })) {
                out.push({iccs:move.iccs,take:enemyValue,give:mineValue,quiet:true});
                break;
            }
        }
    }
    return out;
}

/**
 * 在引擎给的多路候选里，挑一个「非兑子且不亏太多」的替代（2026-09-25 晚修正的安全版避兑）。
 *
 * 背景（真机教训）：第一版是**硬排除**兑子着法 —— 结果 21:25:33 那局把一步「车兑车」硬排掉，
 * 引擎只能走 d6d4，评分从 0 直接崩到 **-314**（亏三个兵）⇒ 局面崩盘输棋。
 * 现在改成：**首选是兑子时，才在候选里挑替代**；替代必须不亏太多（差 ≤ slack，默认 20cp）；
 * 找不到就**照走兑子**（宁可和，绝不因为我们的干预走亏棋）。
 *
 * @param candidates 引擎多路候选（[{move, scoreCp, mate, ...}]，按路数从好到差）
 * @param swapList   兑子着法表（swapMoves 的产物，iccs 数组）
 * @param options { slack?（默认 20） }
 * @returns { iccs, index, gap } 或 null（null = 不干预，按引擎首选走）
 */
function preferAlternative(candidates, avoidList, options) {
    const opts = options || {};
    const slack = opts.slack == null ? 20 : Math.max(0, Number(opts.slack) || 0);
    if (!candidates || !candidates.length || !avoidList || !avoidList.length) return null;
    const best = opts.bestMove
        ? candidates.filter(function (item) { return item && item.move === opts.bestMove; })[0]
        : candidates[0];
    // null 不能通过 Number(null) 变成 0；杀棋和缺失评分都保持引擎原着法。
    if (!best || !best.move || best.mate != null || typeof best.scoreCp !== "number" ||
        !isFinite(best.scoreCp) || avoidList.indexOf(best.move) < 0) return null;
    const flip = opts.side === "b" ? -1 : 1;
    const bestScore = best.scoreCp * flip;
    if ((opts.minScore != null && bestScore < opts.minScore) ||
        (opts.maxScore != null && bestScore > opts.maxScore)) return null;
    const excludes = opts.excludes || [];
    let alternative = null;
    for (let i = 0; i < candidates.length; i++) {
        const item = candidates[i];
        if (!item || !item.move || item.mate != null || avoidList.indexOf(item.move) >= 0 ||
            excludes.indexOf(item.move) >= 0 || typeof item.scoreCp !== "number" ||
            !isFinite(item.scoreCp)) continue;
        // MultiPV 到时可能只来得及把头名推进一层：第 2 路仍停在上一层。
        // 2026-09-28 的 75 回合和棋：f8g6 (+10, 22 层) 重复，
        // 本机同盘复算 h5g5 只低 3～4cp。允许高深度候选差一层，
        // 但把这类比较的分差上限收紧到 10cp；更浅的结果仍不可比。
        const depthLag = best.depth == null ? 0 : best.depth - item.depth;
        // 只为重复着法放宽；兑子与其他调用仍要求同层。
        const onePlyRepeat = opts.onePlyRepeat && opts.onePlyRepeat.indexOf(best.move) >= 0;
        if (best.depth != null && (item.depth == null || depthLag > 1 ||
            (depthLag === 1 && (!onePlyRepeat || best.depth < 18)))) continue;
        const gap = bestScore - item.scoreCp * flip;
        if (gap < 0 || gap > (depthLag === 1 ? Math.min(slack, 10) : slack)) continue;
        if (!alternative || gap < alternative.gap) alternative = {iccs:item.move,index:i,gap:gap};
    }
    return alternative;
}

function preferNonSwap(candidates, swapList, options) {
    return preferAlternative(candidates, swapList, options);
}

module.exports = {
    keyOf: keyOf,
    createSeenPositions: createSeenPositions,
    createConfirmedShuttles: createConfirmedShuttles,
    repeatMoves: repeatMoves,
    swapMoves: swapMoves,
    quietTradeMoves: quietTradeMoves,
    preferNonSwap: preferNonSwap,
    preferAlternative: preferAlternative,
    valueOfPiece: valueOfPiece
};
