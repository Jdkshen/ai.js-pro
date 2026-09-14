// @engine quickjs
/**
 * 落子确认（任务书 14 的确认表）。
 *
 * 只用**新观测**判断棋子到底有没有走到目标格，五种结论：
 *
 * | 结论 | 含义 | 上层动作 |
 * |---|---|---|
 * | CONFIRMED | 与预期己方着法后局面一致 | 记录成功，等对方 |
 * | OPPONENT_REPLY | 连对方一个合法应手都出现了 | 连续记两步，仍是我方走 |
 * | UNCHANGED | 还是走之前那一步没动 | 不算成功；按选择状态决定等待/恢复/有限重试 |
 * | UNEXPECTED | 是别的可靠局面 | 停止旧票据，解析/恢复 |
 * | UNRELIABLE | 不完整/不稳定/几何不可信 | 等新观测，超时再恢复 |
 *
 * 注意：UNRELIABLE 与「没变化」完全不是一回事 —— 把「看不清」当成「没落子」就会重复点击。
 */
"use strict";

const fenModule = require("../chess/fen.js");
const legal = require("../chess/legal-moves.js");

const RESULT = {
    CONFIRMED: "CONFIRMED",
    OPPONENT_REPLY: "OPPONENT_REPLY",
    UNCHANGED: "UNCHANGED",
    UNEXPECTED: "UNEXPECTED",
    UNRELIABLE: "UNRELIABLE"
};

function opposite(side) {
    return side === "b" ? "w" : "b";
}

function boardOfFen(fen) {
    const parsed = fenModule.parseFen(fen);
    if (!parsed.ok) {
        return null;
    }
    return parsed.board;
}

/**
 * @param input.ticket          票据（要 baseFen 与 expectedAfterFen）
 * @param input.observed        新观测的 90 格字符（'?' = 未知，null = 空）
 * @param input.unknownCells    未知格数量（默认按 observed 数）
 * @param input.stable          观测是否已稳定（时序融合的结论）
 * @param input.geometryTrusted 当前几何是否可信
 * @param input.conflicts       关键冲突数（>0 视为不可靠）
 * @returns { result, reason, board, plies, unknownCells }
 */
function verify(input) {
    const src = input || {};
    const ticket = src.ticket;
    const observed = src.observed;
    const unknownCells = src.unknownCells == null
        ? (observed ? observed.filter(function (ch) { return ch === "?"; }).length : 90)
        : Number(src.unknownCells);
    const base = { board: null, plies: [], unknownCells: unknownCells, patched: null };

    if (!ticket) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "没有票据，无法确认" });
    }
    if (src.stable === false) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "观测还不稳定（等下一次采集）" });
    }
    if (src.geometryTrusted === false) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "棋盘几何不可信（需要重新定位）" });
    }
    if (src.conflicts > 0) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "有 " + src.conflicts + " 处关键冲突" });
    }
    if (!observed || observed.length !== 90) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "观测不是完整的 90 格" });
    }

    const expected = boardOfFen(ticket.expectedAfterFen);
    if (!expected) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "票据的预期局面 FEN 解析不了" });
    }

    /**
     * 未知格处理。
     *
     * 只有两种情形允许继续判，两者的共同点是「那格的兵种是**已知事实**」：
     *   ① 未知格恰好是「我方这步棋的落点」—— 游戏会给刚落的子加高亮/换画法，那一格认不出来
     *     （真机实测：带高亮环的炮会被模型读成马，棋规层又把它降级成 '?'）。
     *   ② 未知格是「我方落点 + 对方唯一一个合法应手的落点」—— 对局节奏快时，**对方的子也会
     *     被画上高亮环**，于是同时有两格未知。对方的落点同样不是猜的：枚举合法应手，
     *     只接受**恰好一种**能对上其余全部格子的情形。
     * 除这两情形外一概 UNRELIABLE（宁可等着，也不要拄着错盘面出手）。
     */
    let subject = observed;
    let patched = null;
    let replyPatched = null;
    const destIndex = destinationIndex(ticket);
    /**
     * 「被游戏装饰弄花」的格子 —— 两种表现都要算进去：
     *   ① 读成 '?'（未知格，老路径）；
     *   ② **读成别的子**：真机实测（K40 + 天天象棋）我方炮吃卒落在卒位上，那一格被读成
     *      「红兵/黑卒」（`(col 2,row 3) p→P`）—— 只按未知格处理的话，这种帧会被判成
     *      「出现了无法解释的局面」→ 票据作废 → 重算重试 → 一手棋白跑十几秒。
     * 落点这一格的兵种是**票据 + 棋规推出来的已知事实**，不是猜；其余格子仍必须完全一致。
     */
    const decorated = [];
    const srcIndex = sourceIndex(ticket);
    for (let i = 0; i < 90; i++) {
        const got = observed[i];
        if (typeof got !== "string" || got === "") {
            continue;
        }
        if (got === "?") {
            decorated.push(i);
            continue;
        }
        if (i === destIndex && expected[i] && got !== expected[i]) {
            // 只有「起点确实已经空了」才当成落点被装饰弄花；否则就是我方还没落子
            // （那种帧必须走 UNCHANGED → 允许一次重试，不能被当成「无法解释」把票据作废）
            const srcCleared = srcIndex == null || observed[srcIndex] === expected[srcIndex];
            if (srcCleared) {
                decorated.push(i);
            }
        }
    }
    if (decorated.length > 0 || unknownCells > 0) {
        const dest = destIndex;
        const unknown = decorated;   // 含「读成 ?」与「落点格读成别的子」两类（见上面的说明）
        const onlyDestination = dest != null && unknown.length > 0 &&
            unknown.every(function (index) { return index === dest; });
        if (onlyDestination) {
            subject = observed.slice();
            subject[dest] = expected[dest];
            patched = { index: dest, char: expected[dest] };
        } else {
            // 对方应手也带高亮环 → 枚举“我方落点 + 某一个合法应手落点”的未知格组合
            const replies = legal.allLegalMoves(expected, opposite(ticket.baseSide));
            const fits = [];
            for (let i = 0; i < replies.length; i++) {
                const reply = replies[i];
                const replyDest = indexOfIccs(reply.iccs);
                if (replyDest == null || replyDest === dest) {
                    continue;
                }
                let allowed = true;
                for (let k = 0; k < unknown.length; k++) {
                    const index = unknown[k];
                    if (index !== dest && index !== replyDest) {
                        allowed = false;
                        break;
                    }
                }
                if (!allowed) {
                    continue;
                }
                const after = legal.applyMove(expected, reply).board;
                const candidate = observed.slice();
                if (dest != null) {
                    candidate[dest] = expected[dest];
                }
                candidate[replyDest] = after[replyDest];
                if (legal.boardMatches(after, candidate)) {
                    fits.push({ reply: reply, board: candidate, replyDest: replyDest });
                }
            }
            if (fits.length !== 1) {
                return Object.assign({}, base, {
                    result: RESULT.UNRELIABLE,
                    reason: "还有 " + unknownCells + " 个未知格" +
                        (fits.length > 1
                            ? "（且对方应手有 " + fits.length + " 种解释，补齐不唯一）"
                            : "（且不全是落点格）")
                });
            }
            subject = fits[0].board;
            if (dest != null) {
                patched = { index: dest, char: expected[dest] };
            }
            replyPatched = { index: fits[0].replyDest, char: subject[fits[0].replyDest] };
        }
    }

    if (legal.boardMatches(expected, subject)) {
        return Object.assign({}, base, {
            result: RESULT.CONFIRMED,
            reason: patched
                ? "与预期落子后局面一致（落点格被游戏画花了，按票据已知的兵种补齐 1 格）"
                : "与预期落子后局面一致",
            board: subject.slice(),
            plies: [ticket.moveICCS],
            patched: patched
        });
    }

    // 对方是不是已经应了一步（点击确认期间很常见）
    const replies = legal.allLegalMoves(expected, opposite(ticket.baseSide));
    const matched = [];
    const decorTolerant = [];
    const ownDestIndex = destinationIndex(ticket);
    for (let i = 0; i < replies.length; i++) {
        const after = legal.applyMove(expected, replies[i]).board;
        if (legal.boardMatches(after, subject)) {
            matched.push(replies[i]);
            continue;
        }
        // ---- 装饰容差（真机 2026-09-14）----
        // 游戏会给**刚落的两个子**都画高亮环：我方落点被读成同色不同兵种（红炮→红兵），
        // 对方应手的落点有时直接**漏检（读成空）**。这种帧严格匹配必然 0 解 →
        // 旧行为判 UNEXPECTED → 作废票据 + 重新定位（实测白烧十几秒）。
        // 收紧点：差异只允许落在**我方落点 + 该应手的落点**这两格上，每格必须是
        // 「同色不同兵种」或「读成空」，且必须**唯一**解释；否则照样不认。
        const replyDest = indexOfIccs(replies[i].iccs);
        if (replyDest == null || replyDest === ownDestIndex) {
            continue;
        }
        const diff = legal.diffBoards(after, subject);
        if (diff.length === 0 || diff.length > 2) {
            continue;
        }
        let usable = true;
        for (let k = 0; k < diff.length; k++) {
            const index = diff[k].row * 9 + diff[k].col;
            if (index !== ownDestIndex && index !== replyDest) {
                usable = false;
                break;
            }
            if (!decorTolerable(after[index], subject[index])) {
                usable = false;
                break;
            }
        }
        if (usable) {
            decorTolerant.push({ move: replies[i], board: after, replyDest: replyDest });
        }
    }
    if (matched.length === 1) {
        // ★ 对方应手的落点格**无条件**给出已知事实（不只在我们用过它的时候）：
        // 游戏会给刚落的子画高亮环，那一格在**后续几帧**里经常直接漏检（读成空）→
        // 驱动那边没东西可补 → 盘面与「我方落子 + 对方应手」对不上 → 永久 RECOVERING。
        // 落点兵种是推出来的已知事实（唯一合法应手 + 棋规），不是猜。
        let patch = replyPatched;
        if (!patch) {
            const replyDest = indexOfIccs(matched[0].iccs);
            if (replyDest != null) {
                const afterBoard = legal.applyMove(expected, matched[0]).board;
                if (afterBoard[replyDest]) {
                    patch = { index: replyDest, char: afterBoard[replyDest] };
                }
            }
        }
        return Object.assign({}, base, {
            result: RESULT.OPPONENT_REPLY,
            reason: "我方 " + ticket.moveICCS + " 已落子，对方应手 " + matched[0].iccs +
                (replyPatched
                    ? "（双方落点都被游戏高亮盖住，均按已知事实补齐）"
                    : (patched ? "（落点格被游戏画花了，按票据已知的兵种补齐 1 格）" : "")),
            board: subject.slice(),
            plies: [ticket.moveICCS, matched[0].iccs],
            patched: patched,
            replyPatch: patch
        });
    }
    if (matched.length > 1) {
        return Object.assign({}, base, {
            result: RESULT.UNRELIABLE,
            reason: "对方应手有 " + matched.length + " 种解释，无法唯一确定"
        });
    }
    // 严格匹配一个都没有，但**装饰容差**下恰好只有一个解释 → 认它（并回传改正后的盘面）
    if (matched.length === 0 && decorTolerant.length === 1) {
        const fit = decorTolerant[0];
        return Object.assign({}, base, {
            result: RESULT.OPPONENT_REPLY,
            reason: "我方 " + ticket.moveICCS + " 已落子，对方应手 " + fit.move.iccs +
                "（两个落点格被游戏高亮装饰读错，已按该应手改正）",
            board: fit.board.slice(),
            plies: [ticket.moveICCS, fit.move.iccs],
            replyPatch: fit.board[fit.replyDest]
                ? { index: fit.replyDest, char: fit.board[fit.replyDest] }
                : null
        });
    }
    if (matched.length === 0 && decorTolerant.length > 1) {
        return Object.assign({}, base, {
            result: RESULT.UNRELIABLE,
            reason: "装饰容差下有 " + decorTolerant.length + " 种应手解释，不唯一 → 等下一帧"
        });
    }

    const baseBoard = boardOfFen(ticket.baseFen);
    if (baseBoard && legal.boardMatches(baseBoard, subject)) {
        return Object.assign({}, base, {
            result: RESULT.UNCHANGED,
            reason: "局面仍是走之前的样子（可能只选中了棋子，没落子）",
            board: subject.slice()
        });
    }

    // 「还在动」的帧（真机实测很常见）：我方那个子**两端都画着** —— 起点上还留着一个、
    // 落点上按预期也已经有了（动画/击中特效/残影）→ 这种帧什么结论都不能下，
    // 但**也不该把票据作废**：再等一帧就正常了（状态机的 UNRELIABLE 分支会立刻安排下一次确认）。
    // ⚠️ 判据必须带「落点已按预期出现」这一条：只凭「起点还占着」会把**任何别的局面**都算成动画
    // （自检就抓到了：把黑车凭空少一个的无关局面判成 UNRELIABLE，而不是 UNEXPECTED）。
    const srcIndex2 = indexOfIccsSquare(ticket && ticket.moveICCS ? ticket.moveICCS : "", 0);
    const destIndex2 = indexOfIccsSquare(ticket && ticket.moveICCS ? ticket.moveICCS : "", 1);
    const bothEndsDrawn = baseBoard && srcIndex2 != null &&
        subject[srcIndex2] === baseBoard[srcIndex2] &&
        subject[srcIndex2] !== expected[srcIndex2] &&
        destIndex2 != null && subject[destIndex2] === expected[destIndex2];
    if (bothEndsDrawn) {
        return Object.assign({}, base, {
            result: RESULT.UNRELIABLE,
            reason: "我方那个子还画在起点、落点已变（动画中）→ 等下一帧再确认"
        });
    }

    const diff = baseBoard ? legal.diffBoards(baseBoard, subject) : [];
    // 「颜色读反」的装饰噪声（真机决定性证据 2026-09-14）：
    // 与**基准**逐格比，若差异**全是同字母的大小写不同**（p→P / c→C），而且**不涉及本手起点**
    // （真的走子一定伴随起点变化），那就只是那一格的颜色被判反了 → 局面其实没变 → UNCHANGED。
    // 真机现场：炮二平五之后点 c2c6 没走成（炮还稳稳留在起点），落点的黑卒被读成红兵；
    // 旧逻辑把它当成"动画帧"一直等（65 次 × 2.5s 白等一整轮）。
    // 为什么敢判 UNCHANGED：吃子一定同时改变起点（我方子离开那格），所以「只有同格换色」不可能是吃子。
    const srcIccs = indexOfIccsSquare(ticket && ticket.moveICCS ? ticket.moveICCS : "", 0);
    const destIccs = indexOfIccsSquare(ticket && ticket.moveICCS ? ticket.moveICCS : "", 1);
    if (baseBoard && diff.length > 0) {
        const onlyColourFlips = diff.every(function (item) {
            const index = item.row * 9 + item.col;
            if (index === srcIccs) {
                return false;
            }
            const before = baseBoard[index];
            const after = subject[index];
            return typeof before === "string" && typeof after === "string" &&
                before !== after && before.toLowerCase() === after.toLowerCase();
        });
        if (onlyColourFlips) {
            return Object.assign({}, base, {
                result: RESULT.UNCHANGED,
                reason: "局面仍是走之前的样子（" + diff.length + " 格颜色读反，已按基准纠正）",
                board: baseBoard.slice()
            });
        }
    }
    // 小差异（≤2 格）**且差异都落在本手起点/落点上** → 先当「没看清 / 还在动」，等下一帧重新确认；
    // 差异在别处（或 ≥3 格）→ 局面真的变成了我们解释不了的样子 → UNEXPECTED → 让驱动重算。
    const nearMove = diff.length > 0 && diff.length <= 2 && diff.every(function (item) {
        const index = item.row * 9 + item.col;
        return index === srcIccs || index === destIccs;
    });
    if (nearMove) {
        return Object.assign({}, base, {
            result: RESULT.UNRELIABLE,
            reason: "起点/落点附近有 " + diff.length + " 格对不上（多半是装饰/动画帧）→ 等下一帧再确认"
        });
    }
    return Object.assign({}, base, {
        result: RESULT.UNEXPECTED,
        reason: "出现了无法解释的局面（与基准差 " + diff.length + " 格：" +
            diff.slice(0, 6).map(function (item) {
                return "(col " + item.col + ",row " + item.row + ") " +
                    (item.before || "空") + "→" + (item.after || "空");
            }).join("；") + (diff.length > 6 ? " …" : "") + "）",
        board: subject.slice()
    });
}

/**
 * 落点格「读错」到什么程度还能容忍（装饰容差专用）：
 *   · 同色不同兵种（红炮被读成红兵）；
 *   · **读成空 / 未知**（漏检 —— 高亮环把子盖住时模型直接看不到那一格）。
 * 异色不能容忍：那说明真被对方吃了，不能当噪声吞掉。
 */
function decorTolerable(expected, observed) {
    if (typeof expected !== "string" || expected.length !== 1) {
        return false;
    }
    if (observed == null || observed === "" || observed === "?") {
        return true;
    }
    if (typeof observed !== "string" || observed.length !== 1) {
        return false;
    }
    return (expected === expected.toUpperCase()) === (observed === observed.toUpperCase());
}

/**
 * 票据这步棋的**落点**在 90 格里的下标。
 * ICCS 记法：文件字母 a..i + 行号 9..0（红方底线 = 9），与 rules.moveToIccs 对称。
 */
function destinationIndex(ticket) {
    return indexOfIccsSquare(ticket && ticket.moveICCS ? ticket.moveICCS : "", 1);
}

/** 票据这步棋的**起点**在 90 格里的下标（“起点是否已空”用来分情况，见 decorated 的说明）。 */
function sourceIndex(ticket) {
    return indexOfIccsSquare(ticket && ticket.moveICCS ? ticket.moveICCS : "", 0);
}

/** ICCS 里的第 which 格（0=起点 / 1=落点）在 90 格里的下标；格式不对返回 null。 */
function indexOfIccsSquare(iccs, which) {
    const text = iccs == null ? "" : String(iccs);
    if (!/^[a-i][0-9][a-i][0-9]$/.test(text)) {
        return null;
    }
    const offset = which === 0 ? 0 : 2;
    const col = "abcdefghi".indexOf(text.charAt(offset));
    const row = 9 - Number(text.charAt(offset + 1));
    return row * 9 + col;
}

/** ICCS 着法的**落点**在 90 格里的下标；格式不对返回 null。 */
function indexOfIccs(iccs) {
    return indexOfIccsSquare(iccs, 1);
}

/** 结论是否算「我方真的走成了」（CONFIRMED 与 OPPONENT_REPLY 都算）。 */
function isSuccess(result) {
    return result === RESULT.CONFIRMED || result === RESULT.OPPONENT_REPLY;
}

function describe(verdict) {
    if (!verdict) {
        return "没有确认结果";
    }
    return verdict.result + "：" + verdict.reason;
}

module.exports = {
    RESULT: RESULT,
    verify: verify,
    isSuccess: isSuccess,
    describe: describe,
    destinationIndex: destinationIndex
};
