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
 * @param input.trustDestReading 为 true 时，落点格读到别的子**不再**按票据改回（见下面的说明）。
 *                               只给驱动的「同字异色复议」用：那一条路已经用连续多帧证明「读数是稳的」。
 * @param input.predictedReplies {string[]} 可选：引擎预判的对方应手（ICCS）。
 *                               只在**多解平局**时当裁决 —— 预判不在「严格对得上观测」的候选里时
 *                               完全按老路（预测错 = 当没预测）。
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

    /**
     * ★ 预判裁决（2026-09-25 轻量版）：引擎预判的对方应手（ICCS 数组）。
     * 只在**多解平局**时用它从「严格对得上观测」的候选中挑一个；
     * 预判不在候选里（或仍不唯一）就完全按老路 —— 预测错 = 当没预测。
     */
    const prediction = Array.isArray(src.predictedReplies) && src.predictedReplies.length
        ? src.predictedReplies.map(function (value) { return String(value); })
        : null;
    /** 本次裁决是否用了预判（写进原因文本，方便日志核对） */
    let predictionNote = "";
    /** 从候选列表里按预判缩到唯一；缩不到唯一返回 null（照旧多解） */
    function pickByPrediction(list, iccsOf) {
        if (!prediction || !list || list.length <= 1) {
            return null;
        }
        const picked = list.filter(function (entry) {
            const iccs = iccsOf(entry);
            return !!iccs && prediction.indexOf(String(iccs)) >= 0;
        });
        return picked.length === 1 ? picked : null;
    }

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
            // ★ `trustDestReading`（驱动侧「同字异色复议」专用，2026-09-20）：
            //   落点格读到**别的子**时，不再当「装饰画花」按票据改回 —— 那一帧更可能是
            //   「我方那枚子被对方**同兵种**反吃了」，改回正好把证据抹掉（K40 真机卡死两分钟的真因）。
            //   此时把这一格当**真差异**，留给下面的应手匹配去解释。
            if (src.trustDestReading) {
                continue;
            }
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
        /**
         * ★ 2026-09-20（真机：`确认：UNRELIABLE：还有 1 个未知格（且不全是落点格）` 反复卡确认，
         *   每次白烧一次恢复）：未知格该挡的是**有判别力**的格，不是随便哪一格看不清都不认。
         *
         * 一次走子只能改**起点与落点**两格；别的格子无论几格看不清，都不影响「谁走了哪一步」：
         * 把看不清的格从比对里剔掉，再看候选是不是**唯一**对得上所有看得清的格。
         * 候选只有两类：「我方这手（对方还没应）」与「我方这手 + 某一个合法应手」；
         * 唯一才采信，看不清的那些格就按该候选的值补齐（那两类的值都由票据 + 棋规完全确定）。
         *
         * 旧规则要求未知格必须恰好落在「我方落点 / 我方途经格 / 对方应手落点」上，否则直接 UNRELIABLE
         * ⇒ 木纹压字、水印、任意装饰造成的**任何一格 '?'** 都会让确认超时（真机实测每次 ~15s 白烧）。
         * ⚠️ 唯一性仍是硬要求：若「对方那手的两格」都看不清，「没应手」与「应了那一步」就都能对上
         *   ⇒ 照样 UNRELIABLE（看不清就是看不清，绝不当成没落子）。
         */
        const skip = {};
        for (let k = 0; k < unknown.length; k++) {
            skip[unknown[k]] = true;
        }
        const matchesSkip = function (candidate) {
            for (let i = 0; i < 90; i++) {
                if (skip[i] === true) {
                    continue;   // 看不清的格不参与比对：它的值由候选补齐
                }
                const want = (typeof candidate[i] === "string" && candidate[i] !== "") ? candidate[i] : null;
                const seen = (typeof subject[i] === "string" && subject[i] !== "") ? subject[i] : null;
                if (want !== seen) {
                    return false;
                }
            }
            return true;
        };
        const fits = [];
        if (matchesSkip(expected)) {
            fits.push({ board: expected.slice(), replyDest: null, replyIccs: null });
        }
        const replyMoves = legal.allLegalMoves(expected, opposite(ticket.baseSide));
        for (let i = 0; i < replyMoves.length; i++) {
            const replyDest = indexOfIccs(replyMoves[i].iccs);
            // 应手落点 == 我方落点（对方吃在我方刚落的那枚子上）：那一格的两种解释无法用「看不清」
            // 去区分，交给下面的应手匹配 + allowOwnDestCapture 那一套处理（这里不掺和）。
            if (replyDest == null || replyDest === dest) {
                continue;
            }
            const after = legal.applyMove(expected, replyMoves[i]).board;
            if (matchesSkip(after)) {
                fits.push({ board: after, replyDest: replyDest, replyIccs: replyMoves[i].iccs });
            }
        }
        let fitsUse = fits;
        if (fits.length !== 1 && prediction) {
            const picked = pickByPrediction(fits, function (fit) { return fit.replyIccs; });
            if (picked) {
                fitsUse = picked;
                predictionNote = "按预判裁决：对方应手 " + picked[0].replyIccs;
            }
        }
        if (fitsUse.length !== 1) {
            return Object.assign({}, base, {
                result: RESULT.UNRELIABLE,
                reason: "还有 " + unknownCells + " 个未知格" +
                    (fits.length > 1
                        ? "（且对方应手有 " + fits.length + " 种解释，补齐不唯一）"
                        : "（且看得清的格子对不上这一手）")
            });
        }
        subject = fitsUse[0].board;
        if (dest != null) {
            patched = { index: dest, char: subject[dest] };
        }
        if (fitsUse[0].replyDest != null) {
            replyPatched = { index: fitsUse[0].replyDest, char: subject[fitsUse[0].replyDest] };
        }
    }

    if (legal.boardMatches(expected, subject)) {
        return Object.assign({}, base, {
            result: RESULT.CONFIRMED,
            reason: (patched
                ? "与预期落子后局面一致（落点格被游戏画花了，按票据已知的兵种补齐 1 格）"
                : "与预期落子后局面一致") + (predictionNote ? "｜" + predictionNote : ""),
            board: subject.slice(),
            plies: [ticket.moveICCS],
            patched: patched
        });
    }

    // 对方是不是已经应了一步（点击确认期间很常见）
    const replies = legal.allLegalMoves(expected, opposite(ticket.baseSide));
    const matched = [];
    const decorTolerant = [];
    const ownDestCaptures = [];
    const ownDestIndex = destinationIndex(ticket);
    for (let i = 0; i < replies.length; i++) {
        const after = legal.applyMove(expected, replies[i]).board;
        if (legal.boardMatches(after, subject)) {
            matched.push(replies[i]);
            continue;
        }
        const replyDest = indexOfIccs(replies[i].iccs);
        if (replyDest == null) {
            continue;
        }
        if (replyDest === ownDestIndex) {
            /**
             * ★ 对方**吃在我方刚落的那一枚子上**（同兵种反吃）—— `allowOwnDestCapture` 专用。
             *
             * 真机现场（2026-09-20 v2 局）：我方炮吃对方炮、落点 i7；对方另一枚炮 b7×i7 反吃。
             * 模型把那枚反吃过来的**黑炮直接读成我方颜色**（原始读数 == 期望里的 `C`）
             * ⇒ 「同字异色」分歧根本形不成 ⇒ 永远「装饰容差下有 11 种应手解释，不唯一」
             *（那 11 正是 b7 那门炮的全部合法落点）。
             *
             * 判据很严：差异**只**允许落在这一格，而且必须是「同兵种、颜色相反」。
             * 调用方（驱动）只在一张盘面连续僵持 N 帧之后才开这个开关。
             */
            if (!src.allowOwnDestCapture) {
                continue;
            }
            const onlyThisCell = legal.diffBoards(after, subject);
            if (onlyThisCell.length !== 1) {
                continue;
            }
            if (onlyThisCell[0].row * 9 + onlyThisCell[0].col !== ownDestIndex) {
                continue;
            }
            const want = after[ownDestIndex];
            const got = subject[ownDestIndex];
            if (typeof want !== "string" || typeof got !== "string" ||
                want.length !== 1 || got.length !== 1) {
                continue;
            }
            if (want === got || want.toLowerCase() !== got.toLowerCase()) {
                continue;
            }
            ownDestCaptures.push({ move: replies[i], board: after, replyDest: replyDest });
            continue;
        }
        // ---- 装饰容差（真机 2026-09-14）----
        // 游戏会给**刚落的两枚子**都画高亮环：我方落点被读成同色不同兵种（红炮→红兵），
        // 对方应手的落点有时直接**漏检（读成空）**。这种帧严格匹配必然 0 解 →
        // 旧行为判 UNEXPECTED → 作废票据 + 重新定位（实测白烧十几秒）。
        // 收紧点：差异只允许落在**我方落点 + 该应手的落点**这两格上，每格必须是
        // 「同色不同兵种」或「读成空」，且必须**唯一**解释；否则照样不认。
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
    // ★ 预判裁决（轻量版）：多解时，若预判的应手恰好在「严格对得上观测」的候选里（唯一），用它破平局
    let matchedUse = matched;
    const matchedPick = pickByPrediction(matched, function (move) { return move.iccs; });
    if (matchedPick) {
        matchedUse = matchedPick;
        predictionNote = "按预判裁决：对方应手 " + matchedPick[0].iccs;
    }
    let decorUse = decorTolerant;
    const decorPick = pickByPrediction(decorTolerant, function (item) {
        return item.move ? item.move.iccs : null;
    });
    if (decorPick) {
        decorUse = decorPick;
        predictionNote = "按预判裁决：对方应手 " + decorPick[0].move.iccs;
    }
    if (matchedUse.length === 1) {
        // ★ 对方应手的落点格**无条件**给出已知事实（不只在我们用过它的时候）：
        // 游戏会给刚落的子画高亮环，那一格在**后续几帧**里经常直接漏检（读成空）→
        // 驱动那边没东西可补 → 盘面与「我方落子 + 对方应手」对不上 → 永久 RECOVERING。
        // 落点兵种是推出来的已知事实（唯一合法应手 + 棋规），不是猜。
        let patch = replyPatched;
        if (!patch) {
            const replyDest = indexOfIccs(matchedUse[0].iccs);
            if (replyDest != null) {
                const afterBoard = legal.applyMove(expected, matchedUse[0]).board;
                if (afterBoard[replyDest]) {
                    patch = { index: replyDest, char: afterBoard[replyDest] };
                }
            }
        }
        return Object.assign({}, base, {
            result: RESULT.OPPONENT_REPLY,
            reason: "我方 " + ticket.moveICCS + " 已落子，对方应手 " + matchedUse[0].iccs +
                (replyPatched
                    ? "（双方落点都被游戏高亮盖住，均按已知事实补齐）"
                    : (patched ? "（落点格被游戏画花了，按票据已知的兵种补齐 1 格）" : "")) +
                (predictionNote ? "｜" + predictionNote : ""),
            board: subject.slice(),
            plies: [ticket.moveICCS, matched[0].iccs],
            patched: patched,
            replyPatch: patch
        });
    }
    if (matchedUse.length > 1) {
        return Object.assign({}, base, {
            result: RESULT.UNRELIABLE,
            reason: "对方应手有 " + matchedUse.length + " 种解释，无法唯一确定"
        });
    }
    // 严格匹配一个都没有，但**装饰容差**下恰好只有一个解释 → 认它（并回传改正后的盘面）
    if (matchedUse.length === 0 && decorUse.length === 1) {
        const fit = decorUse[0];
        return Object.assign({}, base, {
            result: RESULT.OPPONENT_REPLY,
            reason: "我方 " + ticket.moveICCS + " 已落子，对方应手 " + fit.move.iccs +
                "（两个落点格被游戏高亮装饰读错，已按该应手改正）" +
                (predictionNote ? "｜" + predictionNote : ""),
            board: fit.board.slice(),
            plies: [ticket.moveICCS, fit.move.iccs],
            replyPatch: fit.board[fit.replyDest]
                ? { index: fit.replyDest, char: fit.board[fit.replyDest] }
                : null
        });
    }
    // ★ 「对方吃在我方落点上」的唯一解释（`allowOwnDestCapture`，见上面的说明）：
    //   回传的盘面必须是 `after` 改正过的（观测那一格的颜色是**错的**）。
    //   ⚠️ 必须排在「装饰容差 > 1」那条之前，而且**不能**要求 decorTolerant 为空 ——
    //   反吃帧在装饰容差下看起来有 N 种解释（那门炮的每个合法落点都"能解释"，代价是
    //   假定那枚子隐形飞走），比"唯一的精确解释"弱得多。探针实测：不这样排就永远走不到这里。
    if (matchedUse.length === 0 && ownDestCaptures.length === 1 && decorUse.length !== 1) {
        const fit = ownDestCaptures[0];
        return Object.assign({}, base, {
            result: RESULT.OPPONENT_REPLY,
            reason: "我方 " + ticket.moveICCS + " 已落子，对方应手 " + fit.move.iccs +
                "（吃在落点上：那一格被模型读成我方颜色，按该应手改正）",
            board: fit.board.slice(),
            plies: [ticket.moveICCS, fit.move.iccs],
            replyPatch: { index: fit.replyDest, char: fit.board[fit.replyDest] }
        });
    }
    if (matchedUse.length === 0 && decorUse.length > 1) {
        return Object.assign({}, base, {
            result: RESULT.UNRELIABLE,
            reason: "装饰容差下有 " + decorUse.length + " 种应手解释，不唯一 → 等下一帧"
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

    // ★ 对方应手端的「两端都画着」（2026-09-20 新局真机）：对方那枚子同时画在**它的起点与落点**上 ——
    //   盘面看起来「多一枚同型子、没有任何格少子」（真机现场：确认观测比期望多 1 枚黑馬、33 枚子，
    //   FEN 只差 1 格）。旧逻辑一路走到最后判 UNEXPECTED —— 作废票据 + 恢复 —— 而随后是
    //   30 秒的「轮次推不出来」空转（那帧既解释不了，也不是真的新局面：动画马上就结束）。
    //   判据刻意收得很窄（下面每条都必须成立，自检里有反例守着）：
    //     ① 恰好一种合法应手，其「期望 + 应手」与观测**只差 1 格**；
    //     ② 那一格正是该应手的**起点**；
    //     ③ 起点上画的那枚子**就是应手那枚子**（同字符），且落点也已按应手画上。
    //   多解或差异在别处 → 照样不认（继续走 UNEXPECTED）。
    const replyInFlight = [];
    for (let i = 0; i < replies.length; i++) {
        const move = replies[i];
        const from = indexOfIccsSquare(move.iccs, 0);
        const to = indexOfIccsSquare(move.iccs, 1);
        if (from == null || to == null) {
            continue;
        }
        const after = legal.applyMove(expected, move).board;
        const gone = legal.diffBoards(after, subject);
        if (gone.length !== 1) {
            continue;
        }
        const index = gone[0].row * 9 + gone[0].col;
        if (index !== from) {
            continue;
        }
        if (!subject[from] || subject[from] !== after[to]) {
            continue;
        }
        if (!subject[to] || subject[to] !== after[to]) {
            continue;
        }
        replyInFlight.push(move.iccs);
    }
    if (replyInFlight.length > 0) {
        return Object.assign({}, base, {
            result: RESULT.UNRELIABLE,
            reason: replyInFlight.length === 1
                ? ("对方应手 " + replyInFlight[0] + " 那枚子还画在起点、落点已变（应手动画中）→ 等下一帧再确认")
                : ("有 " + replyInFlight.length + " 种应手看起来都在动画中，不唯一 → 等下一帧再确认")
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
        // ⚠️ 差异明细必须打出来（2026-09-19）：只报「有 2 格对不上」时无法区分
        //   「真的还在动」与「这手其实已经走成了、只是期望值/基准没对上」——真机为此多跑了三轮。
        return Object.assign({}, base, {
            result: RESULT.UNRELIABLE,
            reason: "起点/落点附近有 " + diff.length + " 格对不上（多半是装饰/动画帧）→ 等下一帧再确认｜" +
                diff.map(function (item) {
                    const index = item.row * 9 + item.col;
                    const role = index === srcIccs ? "起点" : (index === destIccs ? "落点" : "?");
                    return role + "(" + item.col + "," + item.row + ") " +
                        (item.before || "空") + "→" + (item.after || "空") +
                        "（期望 " + (expected[index] || "空") + "）";
                }).join("；") +
                "｜票据着法=" + (ticket.moveICCS || "-") +
                "｜base=" + String(ticket.baseFen || "-").split(" ")[0] +
                "｜期望FEN=" + String(ticket.expectedAfterFen || "-").split(" ")[0]
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

/**
 * 用「当前可靠基准 + 票据着法」重建票据里的两个盘面：`baseFen` 与 `expectedAfterFen`。
 *
 * 为什么要重建（2026-09-19，K40）：验证器读的是票据里**发票据那一刻**冻结的 FEN ——
 * 基准被「按观测改正」过（OCR 读到空格 / 幻影清理）之后，旧期望还把被证伪的子算进去，
 * 验证永远对不上。重建用的是**当前基准 + 这手棋**，两边一起跟着修正。
 *
 * ★★ 2026-09-21 真机（v5 慢局，17/17 次重建全是这个毛病）：重建曾经把着法直接丢给
 *   `legal.applyMove(board, { iccs })` —— 而 applyMove 只认 fromCol/fromRow/toCol/toRow、
 *   **不解析 iccs** ⇒ 静默返回「没走这手」的盘面（还是真值，没人发现）⇒ 期望 == base ⇒
 *   验证器把「真走成了」的观测判成「起点/落点附近有 2 格对不上（动画帧）」⇒ 确认阶段无限等
 *   （真机四笔卡顿 20s / 39s / 120s / 235s 全挂着这条）。
 *   所以这里：**显式把 ICCS 解析成四元坐标**（棋盘数组 row = 9 - rank），并先用
 *   `isLegalMove` 校验这一手在当前基准上真能走；走不通（例如基准已经把这一手消化掉了、
 *   起点已空）返回 null，让调用方保留票据自己的盘面（那才是对的）。
 *
 * @return { baseFen, expectedAfterFen }；重建不了返回 null
 */
function rebuildTicketBoards(ticket, baseBoard) {
    if (!ticket || !ticket.moveICCS || !baseBoard || baseBoard.length !== 90) {
        return null;
    }
    const src = indexOfIccsSquare(ticket.moveICCS, 0);
    const dest = indexOfIccsSquare(ticket.moveICCS, 1);
    if (src == null || dest == null) {
        return null;
    }
    const side = ticket.baseSide === "b" ? "b" : "w";
    const fromCol = src % 9;
    const fromRow = Math.floor(src / 9);
    const toCol = dest % 9;
    const toRow = Math.floor(dest / 9);
    let gate = null;
    try {
        gate = legal.isLegalMove(baseBoard, fromCol, fromRow, toCol, toRow, side);
    } catch (error) {
        gate = null;
    }
    if (!gate || gate.legal !== true) {
        return null;
    }
    const moved = legal.applyMove(baseBoard, {
        iccs: ticket.moveICCS,
        fromCol: fromCol, fromRow: fromRow, toCol: toCol, toRow: toRow
    });
    const board = moved && moved.board ? moved.board : null;
    if (!board) {
        return null;
    }
    const baseFen = fenModule.buildFen(baseBoard, side);
    const afterFen = fenModule.buildFen(board, side);
    if (!baseFen || !afterFen) {
        return null;
    }
    return { baseFen: baseFen, expectedAfterFen: afterFen };
}

/**
 * 票据这步棋的**途经格**（下标 → true）。只有直线移动才会有途经格（马/相/士/兵没有）。
 *
 * 为什么要算它（K40 真实盘：炮二进四吃卒）：游戏会把走子**轨迹**也画出来，
 * 途经格被装饰盖住 → 模型读成 '?'。旧规则只允许「我方落点」与「对方应手落点」两种未知格
 * → 一律判 UNRELIABLE → 每手白白重试/恢复（实测确认阶段连着 50 秒推不出来）。
 * 但途经格的兵种**不是猜的**：直线滑行时全空；炮吃子时中间恰有一枚炮架（期望盘面里写得很清楚）。
 */
function pathIndexesBetween(ticket) {
    const map = {};
    const src = sourceIndex(ticket);
    const dest = destinationIndex(ticket);
    if (src == null || dest == null) {
        return map;
    }
    const srcRow = Math.floor(src / 9);
    const srcCol = src % 9;
    const destRow = Math.floor(dest / 9);
    const destCol = dest % 9;
    if (srcRow === destRow) {
        const step = destCol > srcCol ? 1 : -1;
        for (let col = srcCol + step; col !== destCol; col += step) {
            map[srcRow * 9 + col] = true;
        }
    } else if (srcCol === destCol) {
        const step = destRow > srcRow ? 1 : -1;
        for (let row = srcRow + step; row !== destRow; row += step) {
            map[row * 9 + srcCol] = true;
        }
    }
    return map;
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
    destinationIndex: destinationIndex,
    sourceIndex: sourceIndex,
    // 票据盘面重建（当前基准 + 这手棋）：驱动在「基准被按观测改正过」之后要用（见函数注释）
    rebuildTicketBoards: rebuildTicketBoards,
    // 途经格（直线移动的中间格）：驱动登记「已知事实」时要用（游戏会画走子轨迹盖住它们）
    pathIndexesBetween: pathIndexesBetween
};
