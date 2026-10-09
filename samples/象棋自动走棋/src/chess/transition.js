// @engine quickjs
/**
 * 局面变化解析：从「上一可靠局面 + 新观测」推断发生了什么（任务书 9.3）。
 *
 * 原则：
 * - 唯一合法单步匹配 → 才能确定着法与轮次；
 * - 多个匹配 → 未知，不靠颜色差分硬猜；
 * - 点击确认期间允许「预期己方着法 + 一个合法对方应手」这种两步情形；
 * - 跨过更多着法时**不补造历史**，直接进入恢复。
 *
 * ★ 2026-09-25「多步预判线」接进识别：多解（AMBIGUOUS / 多种两步解释）时，
 *   调用方可以给 `options.preferIccs`（引擎预判的对面着法，按可信度排序）——
 *   若有且只有**一个**候选符合预判，就按它定案（`viaPrediction: true`）。
 *   这是「唯一解」之外**唯一**的例外，而且预判点不出唯一（0 个或并列）时一律保持未知；
 *   调用方（驱动）还会把预判裁决的结果再过一道「连续两帧同一解释」的佐证。
 */
"use strict";

const legal = require("./legal-moves.js");
const rules = require("./rules.js");

const STATUS = {
    MOVE: "MOVE",             // 唯一合法单步
    MOVE_PAIR: "MOVE_PAIR",   // 己方着法 + 对方一个应手
    UNCHANGED: "UNCHANGED",   // 与走之前一致（可能还没落子）
    AMBIGUOUS: "AMBIGUOUS",   // 多个合法解释
    UNKNOWN: "UNKNOWN"        // 无法解释
};

function opposite(side) {
    return side === "w" || side === "W" ? "b" : "w";
}

/**
 * 「预判裁决」：从多解里挑**排名最靠前**的那一个（纯函数，自检直接测）。
 * @param candidates 候选数组
 * @param iccsOf     取候选 ICCS 的函数（单步候选 = m.iccs；两步候选 = p[1].iccs）
 * @param preferIccs 预判着法列表（rank 0 = 最可信；来自引擎主变/对面视角搜索）
 * @returns 点到的唯一候选；0 个或并列（实际不会并列）时返回 null
 */
function pickByPreference(candidates, iccsOf, preferIccs) {
    if (!preferIccs || !preferIccs.length || !candidates || !candidates.length) {
        return null;
    }
    let best = null;
    let bestRank = -1;
    for (let i = 0; i < candidates.length; i++) {
        const iccs = String((iccsOf && iccsOf(candidates[i])) || "");
        const rank = preferIccs.indexOf(iccs);
        if (rank < 0) {
            continue;
        }
        if (bestRank < 0 || rank < bestRank) {
            bestRank = rank;
            best = candidates[i];
        }
    }
    return bestRank >= 0 ? best : null;
}

/**
 * @param before  上一可靠局面的 90 格数组（字符/null）
 * @param side    该走棋方 "w"（红）或 "b"（黑）
 * @param observed 新观测的 90 格数组，未知格用 '?'
 * @param options.allowPair 是否允许「己方 + 对方一步」的两步解释（默认允许）
 * @returns { status, moves, side, nextSide, candidates, reason }
 */
function infer(before, side, observed, options) {
    const opts = options || {};
    const single = legal.matchObserved(before, side, observed);
    if (single.status === "UNIQUE") {
        return {
            status: STATUS.MOVE,
            moves: [single.move],
            side: side,
            nextSide: opposite(side),
            candidates: [single.move],
            reason: "唯一合法单步：" + single.move.iccs
        };
    }
    if (single.status === "NONE" && single.unchanged) {
        return {
            status: STATUS.UNCHANGED,
            moves: [],
            side: side,
            nextSide: side,
            candidates: [],
            board: before.slice(),
            reason: "局面没有变化"
        };
    }

    // 「只是颜色读反」的装饰噪声（真机决定性证据 2026-09-14，K40 + 天天象棋离线局）：
    // 观测与基准只差若干**同字母、大小写不同**的格子（p→P / P→p），其余完全一致
    // → 一步都没走（真走子一定让起点变空，那是「棋子→空」的变化，不可能是同字母换大小写；
    //    吃子也一定同时改变起点）。
    // 现场：我方 炮(c2)、对方 炮(e7) 都还在原位，而卒/兵两格被读出反色
    // → 严格匹配 0 解、容差两步 0 解 → 「轮次推不出来」→ 永久 RECOVERING（一整轮白烧）。
    const flips = legal.diffBoards(before, observed);
    if (flips.length > 0 && flips.every(function (item) {
        return isCaseFlip(item.before, item.after);
    })) {
        return {
            status: STATUS.UNCHANGED,
            moves: [], side: side, nextSide: side, candidates: [],
            board: before.slice(),
            reason: "局面没有变化（" + flips.length + " 格颜色读反，已按基准纠正）"
        };
    }
    if (single.status === "AMBIGUOUS") {
        // ★ 预判裁决：多解时看引擎预判能不能点名（只点一个候选才认；点不到就照旧按未知处理）
        const prefered = pickByPreference(single.candidates, function (m) { return m.iccs; }, opts.preferIccs);
        if (prefered) {
            return {
                status: STATUS.MOVE,
                moves: [prefered],
                side: side,
                nextSide: opposite(side),
                candidates: single.candidates,
                viaPrediction: true,
                reason: "预判裁决：" + prefered.iccs + "（" + single.candidates.length + " 个候选里最符合预判）"
            };
        }
        return {
            status: STATUS.AMBIGUOUS,
            moves: [],
            side: side,
            nextSide: side,
            candidates: single.candidates,
            reason: "有 " + single.candidates.length + " 个合法着法都能得到该局面"
        };
    }
    if (opts.allowPair === false) {
        return {
            status: STATUS.UNKNOWN, moves: [], side: side, nextSide: side, candidates: [],
            reason: "没有合法单步能解释这次变化"
        };
    }

    // 两步解释：己方走一步 + 对方一个合法应手（点击确认期间常见）
    const ownMoves = legal.allLegalMoves(before, side);
    const pairs = [];
    for (let i = 0; i < ownMoves.length; i++) {
        const first = ownMoves[i];
        const midBoard = legal.applyMove(before, first).board;
        const replies = legal.allLegalMoves(midBoard, opposite(side));
        for (let j = 0; j < replies.length; j++) {
            const second = replies[j];
            const endBoard = legal.applyMove(midBoard, second).board;
            if (legal.boardMatches(endBoard, observed)) {
                pairs.push([first, second]);
                if (pairs.length > 8) {
                    // 解释太多说明观测太模糊，早点停下来交给恢复流程
                    return {
                        status: STATUS.AMBIGUOUS, moves: [], side: side, nextSide: side,
                        candidates: [], reason: "两步解释过多（>" + pairs.length + "），按未知处理"
                    };
                }
            }
        }
    }
    if (pairs.length === 1) {
        return {
            status: STATUS.MOVE_PAIR,
            moves: pairs[0],
            side: side,
            nextSide: side,
            candidates: pairs[0],
            board: legal.applyMove(legal.applyMove(before, pairs[0][0]).board, pairs[0][1]).board,
            reason: "己方 " + pairs[0][0].iccs + " + 对方应手 " + pairs[0][1].iccs
        };
    }
    if (pairs.length > 1) {
        // ★ 预判裁决（两步版）：多种两步解释时，看哪种的**对方应手**最符合预判
        const prefered = pickByPreference(pairs, function (p) { return p[1] && p[1].iccs; }, opts.preferIccs);
        if (prefered) {
            return {
                status: STATUS.MOVE_PAIR,
                moves: prefered,
                side: side,
                nextSide: side,
                candidates: pairs,
                viaPrediction: true,
                board: legal.applyMove(legal.applyMove(before, prefered[0]).board, prefered[1]).board,
                reason: "预判裁决：己方 " + prefered[0].iccs + " + 对方应手 " + prefered[1].iccs +
                    "（" + pairs.length + " 种两步解释里最符合预判）"
            };
        }
        return {
            status: STATUS.AMBIGUOUS, moves: [], side: side, nextSide: side,
            candidates: pairs, reason: "有 " + pairs.length + " 种两步解释"
        };
    }

    // 严格两步也失败 → 再试一次**带装饰容差**的两步解释（见函数注释）。
    const tolerant = inferPairWithDecorationTolerance(before, side, observed, opts);
    if (tolerant) {
        return {
            status: STATUS.MOVE_PAIR,
            moves: [tolerant.first, tolerant.second],
            side: side,
            nextSide: side,
            candidates: [tolerant.first, tolerant.second],
            board: tolerant.board,
            // 标成「容差解释」：调用方（驱动）必须追加**跨帧证据**才允许据此行动
            tolerant: true,
            tuned: tolerant.debug,
            reason: "己方 " + tolerant.first.iccs + " + 对方应手 " + tolerant.second.iccs +
                "（落点格被游戏装饰读错，已按着法改正）"
        };
    }
    return {
        status: STATUS.UNKNOWN, moves: [], side: side, nextSide: side, candidates: [],
        debug: lastTolerantDebug,
        reason: "既没有合法单步也没有「一步+应手」能解释，需要恢复"
    };
}

/** 最近一次容差两步的解释情况（诊断用：真机上为什么没匹配上）。 */
let lastTolerantDebug = null;

/** 兵种名（日志用；`transition` 层不该依赖界面，所以就在这儿放一份最小映射）。 */
const TRACK_PIECE_NAME = {
    K: "帅", k: "将", A: "仕", a: "士", B: "相", b: "象",
    N: "马", n: "马", R: "车", r: "车", C: "炮", c: "炮", P: "兵", p: "卒"
};

/**
 * ★ 差分追踪（2026-09-20，用户提的算法）：**先看哪一格动了，再问那个兵种能不能这么走**。
 *
 * 与 `infer()` 的分工：
 *  · `infer()` 是「枚举该方全部合法着法 × 整盘比对」：解释力最强，但**落点格被游戏画花**时
 *    （高亮环把刚落下的子读成别的兵种/颜色 —— 真机 now24 实测 22 次「轮次推不出来」）会全军覆没；
 *  · 这里是「起点空了 + 终点多了个东西 ⇒ 只可能是这一枚子走的」+ `legal.isLegalMove` 的
 *    **兵种规则校验**（马腿 / 象眼 / 炮架 / 九宫 / 过河 / 自将）—— 只看两个格子 + 一段路程，
 *    而且**不依赖落点格读成了什么**：把落点读成 `p`，也照样认得出「炮 C2→C6」。
 *
 * 收得很紧（宁可返回 null 交给 infer，也不乱认）：
 *  ① 差异格里不许有任何 `?`（看不清就不下追踪结论）；
 *  ② 「同字母换大小写」是红黑读反的噪声，不算「多了一个子」；
 *  ③ 单步要求恰好「一个起点空 + 一个终点有子」，两步（我方 + 应手）要求恰好各两个；
 *     每一种配对都要过**兵种规则**，唯一合法的那一种才算数（0 个或 ≥2 个一律返回 null）；
 *  ④ 起点残影（终点收到子、起点却还留着同一个子）只在 `allowSourceGhost` 时认
 *     —— 与 infer 的容差路同一条判据（单帧分不出残影 / 幻觉）。
 *
 * @returns 与 `infer()` 同形状；**返回 null = 追踪给不出结论**，调用方去跑 `infer()`。
 */
function inferTracked(before, side, observed, options) {
    const opts = options || {};
    if (!before || !observed || before.length !== observed.length) {
        return null;
    }
    const diff = legal.diffBoards(before, observed);
    if (diff.length === 0) {
        return null;                                  // 没变化 ⇒ 交给 infer 判 UNCHANGED
    }
    // ---- 分类：① 起点空了（**那个棋不在那个位置了** —— 用户口径）② 终点多了子（含吃子换子）
    //          ③ 读成 '?'（多半是刚落点的光环：看不到子，但那一格显然有东西）
    const vacated = [];
    const arrived = [];
    const blurred = [];
    for (let k = 0; k < diff.length; k++) {
        const item = diff[k];
        const wasThere = item.before;
        const seen = item.after;
        if (wasThere === "?") {
            return null;                              // 可靠基准不含 '?' ⇒ 不该发生
        }
        if (seen === "?") {
            blurred.push(item.index);
            continue;
        }
        const had = typeof wasThere === "string" && wasThere.length === 1;
        const has = typeof seen === "string" && seen.length === 1;
        if (had && has && isCaseFlip(wasThere, seen)) {
            continue;                                 // 红黑读反：那格没动，纯噪声
        }
        if (had && !has) {
            vacated.push(item.index);                 // ★ 那个棋不在那个位置了
        } else if (has) {
            arrived.push(item.index);                 // 多了子（含「换了子」= 吃子）
        }
    }
    const red = side === "w" || side === "W";

    /** 校验「src → dest」这一步是不是该走棋方（moverRed）的合法着法；不是就返回 null。 */
    function stepOf(board, src, dest, moverRed) {
        const piece = board[src];
        if (typeof piece !== "string" || piece.length !== 1 || piece === "?") {
            return null;
        }
        if (rules.isRedPiece(piece) !== moverRed) {
            return null;                              // 起点那枚子不是该方走的
        }
        const fromCol = src % 9;
        const fromRow = Math.floor(src / 9);
        const toCol = dest % 9;
        const toRow = Math.floor(dest / 9);
        const verdict = legal.isLegalMove(board, fromCol, fromRow, toCol, toRow,
            moverRed ? "w" : "b");
        if (!verdict.legal) {
            return null;
        }
        return {
            fromCol: fromCol, fromRow: fromRow, toCol: toCol, toRow: toRow,
            iccs: rules.moveToIccs({ fromCol: fromCol, fromRow: fromRow, toCol: toCol, toRow: toRow }),
            piece: piece
        };
    }

    /**
     * 这一步能不能解释整张观测（**只看起点/落点两格，别处不许有真实变化**）：
     *   · 起点：必须空（或开 `allowSourceGhost` 时的残影）；
     *   · 落点：**随便** —— 高亮环/木纹会把刚落下的子读成别的兵种、别的颜色、甚至只读得出 '?'；
     *     那格是什么子是着法推出来的已知事实，按着法改正；
     *   · 别处：只容忍「同字母换大小写」的红黑读反。
     * 另外要求**落点看得到东西**（子或 '?'）：两边都读空多半是「子还在飞」，不能算走完。
     */
    function fits(move) {
        const applied = legal.applyMove(before, move);
        const src = move.fromRow * 9 + move.fromCol;
        const dest = move.toRow * 9 + move.toCol;
        const probe = legal.diffBoards(applied.board, observed);
        for (let k = 0; k < probe.length; k++) {
            const item = probe[k];
            const index = item.index;
            if (index === src) {
                if (isCaseFlip(item.before, item.after)) {
                    continue;
                }
                if (opts.allowSourceGhost && isSourceGhost(before, item, index)) {
                    continue;
                }
                return false;
            }
            if (index === dest) {
                continue;                             // 落点随便（光环/看不清/读成敌色都行）
            }
            if (isCaseFlip(item.before, item.after)) {
                continue;                             // 别处红黑读反：那格没动
            }
            return false;                             // 还有别的真实变化 ⇒ 不是这一步
        }
        const atDest = observed[dest];
        if (typeof atDest !== "string" || atDest.length !== 1) {
            return false;                             // 落点整格空的 ⇒ 多半是「子还在飞」，不算走完
        }
        // 「落点看得到东西」要分吃子 / 非吃子（自检抓过一次：吃子时敌子还在原地也被当成「到了」）：
        //   · 吃子（那格原来有敌子）⇒ 那枚敌子必须**已经不在**（读成我们的子、读成别的、或 '?' 都行）；
        //   · 非吃子（那格原来空）⇒ 看到子或 '?' 就行。
        const wasAtDest = before[dest] || null;
        if (wasAtDest) {
            return atDest !== wasAtDest;
        }
        return true;
    }

    if (vacated.length <= 1) {
        // ---- 单步：**只枚举那一枚子自己的合法走法**（不是全方几十手）——
        //      「按对方的原来位置扫描：那个棋不在那个位置了 ⇒ 按它的走法规则找它去了哪儿」
        let sources = vacated.slice();
        if (vacated.length === 0) {
            if (!opts.allowSourceGhost) {
                return null;
            }
            // 起点残影：终点那枚子来自基准里哪一格？同色的子可能不止一枚，逐个试（规则 + fits 会筛）
            const anchor = arrived.length ? arrived[0] : (blurred.length ? blurred[0] : -1);
            const seenPiece = anchor >= 0 ? observed[anchor] : null;
            if (typeof seenPiece !== "string" || seenPiece.length !== 1 || seenPiece === "?") {
                return null;                          // 看不清那枚子是什么 ⇒ 找不到来源
            }
            for (let i = 0; i < before.length; i++) {
                if (sameColour(before[i], seenPiece)) {
                    sources.push(i);
                }
            }
            if (sources.length === 0 || sources.length > 4) {
                return null;                          // 同色子太多，说不准是哪一枚 ⇒ 交给 infer
            }
        }
        const found = [];
        for (let i = 0; i < sources.length && found.length < 2; i++) {
            const src = sources[i];
            const piece = before[src];
            if (typeof piece !== "string" || piece.length !== 1 || piece === "?") {
                continue;
            }
            if (rules.isRedPiece(piece) !== red) {
                continue;                             // 不是该走棋方的子
            }
            let list = [];
            try {
                list = legal.legalMoves(before, src % 9, Math.floor(src / 9));
            } catch (error) {
                list = [];
            }
            for (let j = 0; j < list.length && found.length < 2; j++) {
                if (!fits(list[j])) {
                    continue;
                }
                found.push({
                    fromCol: list[j].fromCol, fromRow: list[j].fromRow,
                    toCol: list[j].toCol, toRow: list[j].toRow,
                    iccs: rules.moveToIccs(list[j]),
                    piece: piece
                });
            }
        }
        if (found.length !== 1) {
            return null;                              // 0 = 这一步解释不了；≥2 = 不唯一 ⇒ 交给 infer
        }
        const move = found[0];
        const applied = legal.applyMove(before, move);
        return {
            status: STATUS.MOVE,
            moves: [move],
            side: side,
            nextSide: opposite(side),
            candidates: [move],
            board: applied.board,
            tracked: true,
            // reason 必须**逐帧稳定**（驱动拿它做「同一解释连续两帧一致」的跨帧佐证），
            // 所以别把「落点那一帧读成了什么」写进来。
            reason: "按差分追踪：" + move.iccs + "（" + (TRACK_PIECE_NAME[move.piece] || move.piece) +
                (applied.captured ? " 吃 " + (TRACK_PIECE_NAME[applied.captured] || applied.captured) : "") +
                "）"
        };
    }

    if (vacated.length === 2 && arrived.length === 2 && opts.allowPair !== false) {
        // ---- 两步（我方一步 + 对方应手，确认期间常见）：两种配对都试，唯一合法的才算数
        const orders = [[0, 1], [1, 0]];
        const found = [];
        for (let p = 0; p < orders.length; p++) {
            const first = stepOf(before, vacated[orders[p][0]], arrived[orders[p][0]], red);
            if (!first) {
                continue;
            }
            const mid = legal.applyMove(before, first).board;
            const second = stepOf(mid, vacated[orders[p][1]], arrived[orders[p][1]], !red);
            if (!second) {
                continue;
            }
            found.push({ first: first, second: second, board: legal.applyMove(mid, second).board });
        }
        if (found.length !== 1) {
            return null;
        }
        const pair = found[0];
        return {
            status: STATUS.MOVE_PAIR,
            moves: [pair.first, pair.second],
            side: side,
            nextSide: side,
            candidates: [pair.first, pair.second],
            board: pair.board,
            tracked: true,
            reason: "按差分追踪：我方 " + pair.first.iccs + " + 对方应手 " + pair.second.iccs
        };
    }
    return null;
}

/** ICCS 记法（文件字母 a..i + 行号 9..0）里第 which 个端点的 90 格下标；非法返回 -1。 */
function squareIndex(iccs, which) {
    const text = String(iccs || "");
    if (!/^[a-i][0-9][a-i][0-9]$/.test(text)) {
        return -1;
    }
    const file = "abcdefghi".indexOf(text.charAt(which === 0 ? 0 : 2));
    const rank = Number(text.charAt(which === 0 ? 1 : 3));
    if (file < 0 || !isFinite(rank)) {
        return -1;
    }
    return (9 - rank) * 9 + file;
}

/** 同色（红子的字母为大写、黑子为小写）；非棋子返回 false。 */
function sameColour(a, b) {
    if (typeof a !== "string" || typeof b !== "string" || a.length !== 1 || b.length !== 1) {
        return false;
    }
    return (a === a.toUpperCase()) === (b === b.toUpperCase());
}

/**
 * 是不是「同一个字、只差红黑大小写」的差异（`c` vs `C`）。
 *
 * 真机实测（K40 + 天天象棋，2026-09-14）：模型**会把同一个子的红黑读反**，
 * 而那一格子的子一步都没动 —— 颜色本来就能从基准盘面读出来，属于纯噪声，不该当成「局面变了」。
 */
function isCaseFlip(before, after) {
    return typeof before === "string" && typeof after === "string" &&
        before.length === 1 && after.length === 1 &&
        before !== after && before.toLowerCase() === after.toLowerCase();
}

/**
 * 起点「残影」：那格的子**确实走了**（预测盘面里已经空了），模型却还在原地读得到它。
 *
 * 真机实证（2026-09-20，K40 + 天天象棋，日志 17:42:31）：对方卒 g6g5 之后，观测在
 * 起点 (6,3) 仍有一个卒（同时 (6,4) 也有）—— 差异落在**起点**上，而容差函数原先要求
 * 「起点必须完全一致」⇒ 任何合法着法都解释不了 ⇒ 轮次推不出来 ⇒ 看门狗 ⇒ 恢复（整轮白走）。
 * 离线复现：`.artifacts/tools/probe-ghost-source.js`（把起点抹空，g6g5 立刻被接受）。
 *
 * ⚠️ **单帧分不出「残影」与「其实没走、落点是幻觉」**（两种情况都是「起点与落点各一个子」），
 * 所以默认**不开**（自检 R05e/R05g 的反向保护就靠它）—— 只有驱动在「同一个解释连续两帧
 * 一致」的跨帧佐证下才传 `allowSourceGhost: true` 打开（见 auto-driver 的 tolerantSupportedByTwoFrames）。
 *
 * 判据收得很紧：观测到的那格必须与**走之前**的值完全相同（同一个字，含大小写）——
 * 「有别的子走进了起点」不会与旧值一模一样，所以不会被当成噪声吞掉。
 */
function isSourceGhost(beforeBoard, item, index) {
    if (!beforeBoard || index == null) {
        return false;
    }
    const wasThere = beforeBoard[index];
    if (typeof wasThere !== "string" || wasThere.length !== 1) {
        return false;
    }
    const seen = item && item.after;
    return typeof seen === "string" && seen === wasThere;
}

/**
 * 起点残影能不能算噪声：**必须先在落点看到子到位**（正面证据）。
 *
 * 否则「起点还留着子 + 落点读成空」会被解释成「走了一手」—— 而它更像「这手根本没发生、
 * 落点是漏检」（真机 2026-09-14 那类“第二手没走成”的帧就是长这样）。
 */
function destReceivedPiece(beforeBoard, src, dest, observed) {
    const moved = beforeBoard && beforeBoard[src];
    const seen = observed && observed[dest];
    if (typeof moved !== "string" || moved.length !== 1) {
        return false;
    }
    if (typeof seen !== "string" || seen.length !== 1 || seen === "?") {
        return false;
    }
    return sameColour(moved, seen);
}

/**
 * 带装饰容差的**单步**解释（严格推断与两步解释都失败后，驱动才用它）。
 *
 * 允许两种「模型读错」，其余格子必须与「基准 + 这一步」完全一致：
 *   ① **落点格**被游戏的高亮装饰弄花（读错兵种 / 读成空）—— 落点是着法推出来的已知事实，按着法改正；
 *   ② **没被这一步碰过的格子**只是红黑读反（同字母换大小写）—— 那格子没动，颜色由基准就知道。
 *
 * ⚠️ ② 是 2026-09-14 的新证据（K40 + 天天象棋离线局，b2c2 刚确认成功之后）：
 * 对方应手是「车九进一」(0,0)→(0,1)，那一格带高亮环被读成炮 `c`（① 能接住），
 * 同时另有 (7,2) 的炮被读成红炮 `C`（② 之前**没人接**）→ 差异格数 1 变成 3 →
 * 任何合法着法都解释不了 → 「轮次推不出来」→ 看门狗 → RECOVERING，一整轮再也回不来。
 * 日志实证：基准 `rnbakabnr/9/1c5c1/…/2C4C1/…`，观测 `1nbakabnr/c8/1c5C1/…/2C4C1/…`。
 *
 * 收紧点（宁可交给恢复流程，也不乱认）：
 *  · 起点必须完全一致（子确实离开了原位）—— 所以「只是颜色读反」不会被当成走子；
 *  · ② 只接受**同字母换大小写**，异字母/异色一律当真实变化（吃子不能被噪声吞掉）；
 *  · 必须有**唯一**解释（≥2 个候选一律不认）。
 *
 * @returns { move, iccs, board, nextSide, colorNoise } | null
 */
function inferSingleWithDecorationTolerance(before, side, observed, opts) {
    if (!before || !observed) {
        return null;
    }
    const allowSourceGhost = !!(opts && opts.allowSourceGhost);
    let moves = [];
    try {
        moves = legal.allLegalMoves(before, side);
    } catch (error) {
        return null;
    }
    const fits = [];
    for (let i = 0; i < moves.length && fits.length < 2; i++) {
        const move = moves[i];
        const src = squareIndex(move.iccs, 0);
        const dest = squareIndex(move.iccs, 1);
        if (src < 0 || dest < 0) {
            continue;
        }
        let after = null;
        try {
            after = legal.applyMove(before, move).board;
        } catch (error) {
            continue;
        }
        const diff = legal.diffBoards(after, observed);
        const real = [];
        let colorNoise = 0;
        // 起点残影要**先看到落点有子**才敢认（否则「起点留子 + 落点读空」会被误认成走了一手）
        const ghostOk = allowSourceGhost && destReceivedPiece(before, src, dest, observed);
        for (let k = 0; k < diff.length; k++) {
            const item = diff[k];
            const index = item.row * 9 + item.col;
            if (index !== src && index !== dest && isCaseFlip(item.before, item.after)) {
                colorNoise += 1;   // 同字异色噪声：那格子没动，按基准纠回
                continue;
            }
            // ★ 起点残影（2026-09-20 真机）：那格的子确实走了，模型还在原地读得到它。
            //   只在调用方明确开了 allowSourceGhost 时才算噪声（单帧分不出残影/幻觉）。
            if (ghostOk && index === src && isSourceGhost(before, item, index)) {
                colorNoise += 1;
                continue;
            }
            real.push(index);
        }
        if (real.length > 1) {
            continue;
        }
        if (real.length === 1 && real[0] !== dest) {
            continue;   // 差异不在落点上（别处那格被读错）→ 不采信
        }
        fits.push({ move: move, board: after, colorNoise: colorNoise });
    }
    if (fits.length !== 1) {
        return null;
    }
    return {
        move: fits[0].move,
        iccs: fits[0].move.iccs,
        board: fits[0].board.slice(),
        nextSide: opposite(side),
        colorNoise: fits[0].colorNoise
    };
}

/**
 * 「被盖住的应手」推断：走棋方的起点/落点被游戏「上一步」标记盖住、读不出来，观测层又把那格
 * 按旧基准填回去（未知=没变）⇒ 差分看起来「没动」或「多出来一个子」，两步推断全失败。
 *
 * 真机现场（K40 2026-09-21，败局复盘）：黑炮 b7b5 命中 (1,4) 带标记 ⇒ 每帧「顽固未知 1 格→按旧基准填」
 * ⇒ 盘面永远没变化 ⇒ 轮次停在「轮到对方」5 分钟，直到被对手绝杀。
 *
 * 思路：对方其实已经走完了一手，只是**被盖住的那几格**读不出来。枚举它全部合法着法 m：
 * apply(base, m) 与 observed 的每一处差异都只落在 covered 格上（或「没被这步碰过的格子只是红黑读反」）；
 * 有且只有一个 m 说得通 ⇒ 就是这一手。
 *
 * 收紧点（宁可继续等，也不乱认）：
 *  · src / dest 至少一个在 covered 里（不然这属于普通两步推断的活）；
 *  · 非 covered 格必须**完全一致**（只容忍同字母大小写翻转）；
 *  · 唯一解才认，≥ 2 个候选一律不认。
 *
 * @returns { move, iccs, board, nextSide } | null
 */
function inferSingleThroughCovered(before, side, observed, coveredIndexes) {
    if (!before || !observed || !coveredIndexes || !coveredIndexes.length) {
        return null;
    }
    const covered = {};
    for (let i = 0; i < coveredIndexes.length; i++) {
        covered[coveredIndexes[i]] = true;
    }
    let moves = [];
    try {
        moves = legal.allLegalMoves(before, side);
    } catch (error) {
        return null;
    }
    const fits = [];
    for (let i = 0; i < moves.length && fits.length < 2; i++) {
        const move = moves[i];
        const src = squareIndex(move.iccs, 0);
        const dest = squareIndex(move.iccs, 1);
        if (src < 0 || dest < 0) {
            continue;
        }
        if (!covered[src] && !covered[dest]) {
            continue;   // 这一手解释不了被盖住的格 → 不归这条路管
        }
        let after = null;
        try {
            after = legal.applyMove(before, move).board;
        } catch (error) {
            continue;
        }
        const diff = legal.diffBoards(after, observed);
        let ok = true;
        for (let k = 0; k < diff.length; k++) {
            const item = diff[k];
            const index = item.row * 9 + item.col;
            if (covered[index]) {
                continue;   // 被盖住的格：读不出来的，差异随便
            }
            if (isCaseFlip(item.before, item.after)) {
                continue;   // 同字异色噪声：那格没动
            }
            ok = false;
            break;
        }
        if (!ok) {
            continue;
        }
        fits.push({ move: move, board: after });
    }
    if (fits.length !== 1) {
        return null;
    }
    return {
        move: fits[0].move,
        iccs: fits[0].move.iccs,
        board: fits[0].board.slice(),
        nextSide: opposite(side)
    };
}

/**
 * 落点格「读错」到什么程度还能接受（容差两步专用）：
 *   · 同色不同兵种（红炮读成红兵）—— 游戏高亮装饰的典型误读；
 *   · **读成空** —— 漏检（带高亮环/特效的那一格模型直接看不到），模型已知失效模式。
 * 异色（红↔黑）仍然拒绝：那说明真被对方吃了，不能当噪声吞掉。
 */
function usableAtMoveEnd(expected, observed) {
    if (typeof expected !== "string" || expected.length !== 1) {
        return false;
    }
    if (observed == null || observed === "" || observed === "?") {
        return true;   // 漏检 / 读不出来：落点格是已知事实，按着法补齐
    }
    if (typeof observed !== "string" || observed.length !== 1) {
        return false;
    }
    return sameColour(expected, observed);
}

/**
 * 带装饰容差的**两步**解释：模型把「刚落的那个子」读错兵种时用。
 *
 * 真机现场（2026-09-14，K40 + 天天象棋离线局）：我方 炮(c2)吃卒落在 (2,3)、
 * 对方紧接着 炮(c4)吃我兵落在 (4,6)，而这两格被游戏的高亮装饰影响，
 * 被模型读成了「兵」（**颜色对、兵种错**）→ 与「基准 + 两手」差 2 格
 * → 严格匹配 0 解 → 「轮次推不出来」→ 8s 看门狗 → 永久 RECOVERING（白等一整轮）。
 *
 * 收紧点（宁可交给恢复流程，也不乱认）：
 *  - 差异**只允许落在两手的落点**上：起点必须完全一致 —— 子确实离开了原位；
 *  - 每格必须与原子的**颜色一致**（红黑串色另有专门规则处理，这里不接）；
 *  - 必须有**唯一**解释（≥2 个候选一律判未知）。
 */
function inferPairWithDecorationTolerance(before, side, observed, opts) {
    const allowSourceGhost = !!(opts && opts.allowSourceGhost);
    const ownMoves = legal.allLegalMoves(before, side);
    const debug = { ownMoves: ownMoves.length, tried: 0, minDiff: -1, best: "", fits: 0 };
    lastTolerantDebug = debug;
    const fits = [];
    for (let i = 0; i < ownMoves.length && fits.length < 2; i++) {
        const first = ownMoves[i];
        const firstDest = squareIndex(first.iccs, 1);
        if (firstDest < 0) {
            continue;
        }
        const midBoard = legal.applyMove(before, first).board;
        const replies = legal.allLegalMoves(midBoard, opposite(side));
        for (let j = 0; j < replies.length && fits.length < 2; j++) {
            const second = replies[j];
            const secondDest = squareIndex(second.iccs, 1);
            if (secondDest < 0 || secondDest === firstDest) {
                continue;
            }
            const endBoard = legal.applyMove(midBoard, second).board;
            const diff = legal.diffBoards(endBoard, observed);
            debug.tried += 1;
            if (debug.minDiff < 0 || diff.length < debug.minDiff) {
                debug.minDiff = diff.length;
                debug.best = first.iccs + "+" + second.iccs + ":" + diff.slice(0, 4).map(function (item) {
                    return "(" + item.col + "," + item.row + ")" + (item.before || "空") + ">" +
                        (item.after || "空");
                }).join(",");
            }
            // 差异上限：两手各自的落点装饰（≤2）+ 两个起点的残影（≤2）+ 几格同字异色。
            if (diff.length > 6) {
                continue;
            }
            let usable = true;
            for (let k = 0; k < diff.length; k++) {
                const item = diff[k];
                const index = item.row * 9 + item.col;
                if (index === firstDest || index === secondDest) {
                    if (!usableAtMoveEnd(endBoard[index], observed[index])) {
                        usable = false;
                        break;
                    }
                    continue;
                }
                // 起点残影 / 同字异色：那格子的子没动（或已经走了但模型还在原地读）→ 噪声。
                if (isCaseFlip(item.before, item.after) ||
                    (allowSourceGhost && isSourceGhost(before, item, index))) {
                    continue;
                }
                usable = false;
                break;
            }
            if (usable) {
                fits.push({ first: first, second: second, board: endBoard });
            }
        }
    }
    debug.fits = fits.length;
    return fits.length === 1 ? fits[0] : null;
}

const XQ_TRANSITION = {
    STATUS: STATUS,
    opposite: opposite,
    infer: infer,
    inferTracked: inferTracked,
    isCaseFlip: isCaseFlip,
    squareIndex: squareIndex,
    /** 纯函数：多解里按预判排名点名（自检直接测） */
    pickByPreference: pickByPreference,
    inferSingleWithDecorationTolerance: inferSingleWithDecorationTolerance,
    inferSingleThroughCovered: inferSingleThroughCovered
};

module.exports = XQ_TRANSITION;
