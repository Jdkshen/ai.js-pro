// @engine quickjs
"use strict";

/** 落子确认与恢复：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const verifier = require("../move-verifier.js");
const transition = require("../../chess/transition.js");
const fenModule = require("../../chess/fen.js");
const legal = require("../../chess/legal-moves.js");

function create(driverState, driverActions) {
    function doVerify(effect) {
        /** 这一帧枚举出来的对方应手是否被拒（未得到第二次佐证） */
        let replyRejected = false;
        const ticket = effect.ticket;
        // ★ 2026-09-24 去重：同一张票据 2 秒内**重复**进入确认时，直接复用上一次 ROI 快速确认的结论。
        //   真机实测（K40）：一手棋的确认会被完整跑两次（两条「第 1 次探测」相隔 ~0.6s —— 第二次多花的
        //   抓帧+OCR +300ms 等待白烧）；多次定位未成，先在这里兑底（根源待设备插桩再查）。
        if (
            driverState.lastFastConfirm &&
            ticket &&
            driverState.lastFastConfirm.actionId != null &&
            driverState.lastFastConfirm.actionId === ticket.actionId &&
            driverState.nowMono() - driverState.lastFastConfirm.atMono < 2000
        ) {
            driverActions.note("确认：复用上一轮 ROI 结论（同一票据，省一次抓帧+OCR）");
            return driverActions.pump(
                driverState.machine.dispatch({
                    type: "VERIFY_RESULT",
                    ticket: ticket,
                    verdict: Object.assign({}, driverState.lastFastConfirm.verdict)
                })
            );
        }
        // 把票据的「预期落子后局面」当期望传给观察层：用来纠正「同一兵种颜色读反」的装饰格
        let expectedBoard = null;
        try {
            const parsed = fenModule.parseFen(ticket && ticket.expectedAfterFen);
            expectedBoard = parsed && parsed.ok ? parsed.board : null;
        } catch (error) {
            expectedBoard = null;
        }
        // ★ ROI 快速确认（2026-09-21 提速·方案第 3 条）：先只问起点/终点两格；硬证据齐了就直接成功，
        //   省掉一整轮全盘识别（~1.5s）。拿不出结论就照旧往下走全盘确认（宁慢勿错）。
        if (expectedBoard) {
            const fast = driverActions.tryFastConfirmRoI(ticket, expectedBoard);
            if (fast) {
                driverState.stats.confirms += 1;
                driverState.verifyCycle += 1;
                driverState.stats.roiConfirms += 1;
                // 与全盘确认成功路径保持一致（各自注释见下方对应块）：
                if (fast.fromIndex >= 0) {
                    driverActions.dropPatchesAt([fast.fromIndex], "我方 " + ticket.moveICCS + " 已 ROI 确认，起点已空");
                }
                driverState.activeOwnMove = null;
                const fastMySide = ticket.baseSide === "b" ? "b" : "w";
                const fastNextSide = fastMySide === "w" ? "b" : "w";
                driverState.lastReliable = { board: expectedBoard.slice(), side: fastNextSide };
                // A3 影子：ROI 确认的我方着法入账（账本只记录，不参与决策）
                const roiBaseBoard = driverActions.boardOfFen(ticket.baseFen);
                if (roiBaseBoard) {
                    driverActions.trackConfirm({
                        iccs: ticket.moveICCS,
                        fromBoard: roiBaseBoard,
                        side: ticket.baseSide,
                        afterBoard: expectedBoard,
                        source: "our-click"
                    });
                }
                driverState.decisionEpoch += 1;
                driverActions.note(
                    "确认：CONFIRMED（ROI 快速确认：起点已空 + 落点 " +
                        fast.destChar +
                        "｜第 " +
                        fast.attempts +
                        " 次探测）"
                );
                driverActions.note(
                    "可靠基准已刷新（确认 CONFIRMED）：" + (fastNextSide === fastMySide ? "轮到我方" : "轮到对方")
                );
                driverActions.clearAttemptBudget("已确认真实变化 CONFIRMED（ROI）");
                if (expectedBoard[fast.destIndex]) {
                    driverActions.addPatch(
                        { index: fast.destIndex, char: expectedBoard[fast.destIndex] },
                        "落点格（我方新落的子会被画环｜ROI）"
                    );
                }
                // 途经格登记：与全盘路径同一理由（游戏画的走子轨迹会盖住中间格）
                try {
                    const pathSet = verifier.pathIndexesBetween ? verifier.pathIndexesBetween(ticket) : null;
                    if (pathSet) {
                        const pathKeys = Object.keys(pathSet);
                        for (let i = 0; i < pathKeys.length; i++) {
                            const index = Number(pathKeys[i]);
                            if (expectedBoard[index]) {
                                driverActions.addPatch(
                                    { index: index, char: expectedBoard[index] },
                                    "途经格（游戏画的走子轨迹｜ROI）"
                                );
                            } else {
                                driverActions.addEmptyPatch(index, "途经格（游戏画的走子轨迹，棋规上必为空｜ROI）");
                            }
                        }
                    }
                } catch (error) {
                    /* 途经格登记失败不影响确认 */
                }
                const fastVerdict = {
                    result: verifier.RESULT.CONFIRMED,
                    board: expectedBoard.slice(),
                    plies: [ticket.moveICCS],
                    reason: "ROI 快速确认（起点空 + 落点 " + fast.destChar + "）"
                };
                // 同一票据若紧接着又被确认一次（真机实测会往返两次），直接复用这个结论。
                driverState.lastFastConfirm = {
                    actionId: ticket.actionId,
                    atMono: driverState.nowMono(),
                    verdict: fastVerdict
                };
                return driverActions.pump(
                    driverState.machine.dispatch({
                        type: "VERIFY_RESULT",
                        ticket: ticket,
                        verdict: fastVerdict
                    })
                );
            }
        }
        const observed = driverActions.observeOnce({ forVerify: true, expect: expectedBoard, ticket: ticket });
        if (!observed.ok) {
            // A royal guard pause has already cancelled this ticket. Do not dispatch
            // another verification or let an older snapshot describe the paused tick.
            if (observed.stopRequired || driverState.machine.state() === "PAUSED") return null;
            return driverActions.pump(
                driverState.machine.dispatch({
                    type: "VERIFY_RESULT",
                    ticket: ticket,
                    verdict: { result: verifier.RESULT.UNRELIABLE, reason: observed.reason }
                })
            );
        }
        // ★ 确认盘面也要吃「已知事实」补丁表。
        // 观察路径会用补丁表把游戏给上一步画的装饰格（高亮环/命中标记）补回来，但确认走的是
        // `forVerify + expect`（按期望纠正颜色）那条路，装饰格会残留成 '?' → 验证器一路报
        // 「还有 N 个未知格（且不全是落点格）」→ 既得不出「没走成」也得不出一致，永远卡在确认。
        // 真机现场（K40）：第二手 c2c6 之后连刷 50 秒 UNRELIABLE，整局停住。
        // 只补「登记过、且当前确实是未知」的格子，所以不会凭空造子。
        let verifyChars = driverActions.applyKnownPatchesTo(observed.chars);
        // ★ 已裁定的格子：本票之内一律以**裁定值**为准（见 settledRaw 的说明）。
        //   放在这里（验证之前）而不是事后复议：否则每帧都要等 N 帧僵持才能得出结论。
        if (driverState.settledRaw && driverState.settledRaw.ticketId === ticket.actionId) {
            let touched = false;
            for (let i = 0; i < driverState.settledRaw.cells.length; i++) {
                const cell = driverState.settledRaw.cells[i];
                if (verifyChars[cell.index] !== cell.char) {
                    if (!touched) {
                        verifyChars = verifyChars.slice();
                        touched = true;
                    }
                    verifyChars[cell.index] = cell.char;
                }
            }
        }
        const verifyUnknown =
            verifyChars === observed.chars ? observed.unknownCells : driverActions.countUnknownChars(verifyChars);
        /**
         * 交给验证器的票据：基准刚被「按观测改正」过时，用当前基准 + 我方这手**重建期望值**。
         *
         * 为什么要它（2026-09-19，K40）：验证器读的是 `ticket.expectedAfterFen` ——
         * **发票据那一刻**冻结的 FEN。已知事实被证伪后（OCR 读到空格）基准与补齐表都改对了，
         * 但票据里那张旧期望值还把被证伪的子算进去 ⇒ 验证永远
         * 「装饰容差下有 N 种应手解释，不唯一」，整局钉死（日志里看得清清楚楚：
         * 确认观测没有那枚炮、期望里还有）。
         * ⚠️ 只在改正后的一段时间内重建；平时不重建，免得基准已经走到下一手时算错。
         * ⚠️ 重建的是**副本**，交给状态机的事件仍带原票据（按 id 匹配）。
         */
        let verifyTicket = ticket;
        // ⚠️ 守卫必须带 `baselineCorrectedAt > 0`：自检里用的是**假时钟**（可能从 0 开始计时），
        //    只比「间隔小于 120s」会恒为真 ⇒ 测试里也走重建路径 ⇒ 行为被改坏（2026-09-19 踩过：
        //    自检直接跑不出结果）。
        if (driverState.baselineCorrectedAt > 0 && driverState.nowMono() - driverState.baselineCorrectedAt < 120000) {
            const rebuilt = driverActions.rebuildTicketBoards(ticket);
            if (
                rebuilt &&
                (rebuilt.baseFen !== ticket.baseFen || rebuilt.expectedAfterFen !== ticket.expectedAfterFen)
            ) {
                verifyTicket = Object.assign({}, ticket, rebuilt);
                // 验证和入账必须使用同一套盘面；否则修订后验证成功，账本仍拿旧票据拒收。
                expectedBoard = driverActions.boardOfFen(verifyTicket.expectedAfterFen);
                // 把两个盘面都打出来：验证器的容差判断同时看 baseFen 与 expectedAfterFen，
                // 不写清楚就只能猜（2026-09-19 为此多跑了两轮真机）。
                driverActions.note(
                    "票据里的盘面已按当前基准重建：" +
                        ticket.moveICCS +
                        "｜旧 base=" +
                        (ticket.baseFen || "-").split(" ")[0] +
                        "｜新 base=" +
                        rebuilt.baseFen.split(" ")[0] +
                        "｜旧期望=" +
                        (ticket.expectedAfterFen || "-").split(" ")[0] +
                        "｜新期望=" +
                        rebuilt.expectedAfterFen.split(" ")[0]
                );
            }
        }
        // 冲突明细（2026-09-14 真机）：以前只报「有 N 处关键冲突」，看不见是**哪一格**，
        // 48 次卡死的真因（同字异色抖动）只能靠翻视觉层源码猜。现在把格子与两个候选都打出来，
        // 并把「同字异色」单独计数（那类冲突已不再否决整帧，只在这里留痕）。
        (function noteConflicts() {
            const stats = observed.result && observed.result.stats;
            if (!stats) {
                return;
            }
            const cells = stats.conflictCells || [];
            const text =
                "冲突明细：异字 " +
                (stats.conflicts || 0) +
                " 处、同字异色 " +
                (stats.sameGlyphConflicts || 0) +
                " 处" +
                (cells.length
                    ? "｜" +
                      cells
                          .map(function (item) {
                              return (
                                  item.name +
                                  " " +
                                  item.best +
                                  "/" +
                                  item.second +
                                  "(" +
                                  item.bestScore +
                                  "/" +
                                  item.secondScore +
                                  ")" +
                                  (item.sameGlyph ? "同字" : "异字")
                              );
                          })
                          .join(" ")
                    : "");
            if (text !== driverState.lastConflictText) {
                driverState.lastConflictText = text;
                driverActions.note(text);
            }
        })();
        // 轻量预判：只有「预判基点 == 本票据的预期后局面」时才交给验证器（否则宁可不给）
        const predictedForVerify =
            driverState.predictedReplies &&
            verifyTicket &&
            driverState.predictedReplies.key === verifyTicket.expectedAfterFen
                ? driverState.predictedReplies.list
                : null;
        let verdict = verifier.verify({
            ticket: verifyTicket,
            observed: verifyChars,
            unknownCells: verifyUnknown,
            stable: true,
            geometryTrusted: true,
            conflicts: observed.conflicts,
            predictedReplies: predictedForVerify,
            // ★ 已裁定的票据（见 settledRaw）：**每一帧**都要告诉验证器「落点格的颜色以观测为准」——
            //   否则 `onlyDestination` 分支（落点被装饰画花 → 按票据改回）会把钉住的裁定值又改回期望值，
            //   本票就会永远在「僵持 → 复议 → 确认 → 又僵持」里循环（真机 v3 实测 48 次、5 分钟不动）。
            trustDestReading: !!(driverState.settledRaw && driverState.settledRaw.ticketId === ticket.actionId)
        });
        // Two-frame complete raw readings may already show a fast legal opponent reply.
        // Do not let an old source/destination patch invent a second copy of that piece.
        const raw = observed.rawChars;
        const circles = driverState.lastResult && driverState.lastResult.glyphStats && driverState.lastResult.glyphStats.circleEvidence;
        if (raw && raw.indexOf("?") < 0 && circles && circles.active) {
            const rawVerdict = verifier.verify({ticket:verifyTicket, observed:raw, unknownCells:0,
                stable:true, geometryTrusted:true, conflicts:observed.conflicts,
                predictedReplies:predictedForVerify, trustDestReading:true, allowOwnDestCapture:true});
            const exact = rawVerdict.board && legal.boardMatches(rawVerdict.board, raw);
            if (verifier.isSuccess(rawVerdict.result) && exact && !legal.boardMatches(verifyChars, raw)) {
                verdict=rawVerdict;verifyChars=raw.slice();observed.chars=raw.slice();observed.unknownCells=0;
                driverState.lastBoard=raw.slice();
                driverActions.note("确认采用两帧完整原始盘面：" + verifier.describe(rawVerdict) + "（旧补齐记录不得覆盖真实应手）");
            }
        }
        /**
         * ★ 「同字异色」僵持 → 拿**原始读数**复议一次（2026-09-20，K40 实拍定位）。
         *
         * 症状：整局钉死在 `UNRELIABLE：装饰容差下有 11 种应手解释，不唯一`，连刷两分钟不动。
         * 真机现场：我方炮吃掉对方一枚炮、落在 i7；对方**同一横线上的另一枚炮 b7×i7 反吃**。
         * 观测在 i7 读到的是 `c`（黑炮）—— **读得完全正确**；但
         *   ① 补齐表第 ③ 支把「同字异色」当读反，按已知事实写回 `C`；
         *   ② 期望纠色块又按 `toLowerCase()` 相等把 `c` 翻成 `C`。
         * 两处都把「我方那枚子已经没了」的证据抹掉 ⇒ 观测与期望只剩 b7 变空 ⇒ 看起来像
         * 「炮从 b7 走了、去哪儿没看见」⇒ 装饰容差枚举出的 11 种解释永不唯一（那 11 种正是
         * b7 那门炮的全部合法落点）⇒「等下一帧」等到天荒地老。截图放大核对：i7 上是黑字炮，
         * b7 留着上一步的白圈。
         *
         * 两个「纠色」本身是必要的（游戏装饰确实会让模型把红黑读反），所以不能删；
         * 改成**可证伪**：同字异色的格子连续 `cfg.colorDisputeStreak` 帧不变时，把这些格按
         * 原始读数放回去重新验证，**只有拿到成功结论（CONFIRMED / OPPONENT_REPLY）才采信**。
         * 装饰抖动撑不过连续几帧，真被反吃则一直稳定。
         */
        if (verdict.result === verifier.RESULT.UNRELIABLE && observed.rawChars && observed.chars) {
            const disputed = [];
            for (let i = 0; i < 90; i++) {
                const raw = observed.rawChars[i];
                const fixed = verifyChars[i];
                if (typeof raw !== "string" || typeof fixed !== "string") {
                    continue;
                }
                if (raw !== fixed && /^[A-Za-z]$/.test(raw) && raw.toLowerCase() === fixed.toLowerCase()) {
                    disputed.push(i);
                }
            }
            let disputeKey = "";
            for (let i = 0; i < disputed.length; i++) {
                disputeKey += disputed[i] + ":" + observed.rawChars[disputed[i]] + ",";
            }
            // ★ 第二类僵局：一个「同字异色」分歧都没有，但同一张盘面卡了 N 帧。
            //   真机现场（2026-09-20 第二局）：模型把**反吃过来的那枚黑炮直接读成我方颜色**
            //   （原始读数 == 期望）⇒ 分歧形不成 ⇒ 永远「装饰容差下有 11 种应手解释，不唯一」。
            //   这类僵局交给「对方吃在我方落点」的假设去解释（verifier.allowOwnDestCapture）。
            const disputeKeyResolved = disputeKey || "stuck:" + verifyChars.join("");
            const disputeMode = disputeKey ? "同字异色" : "盘面";
            if (disputeKeyResolved && disputeKeyResolved === driverState.lastColorDisputeKey) {
                driverState.lastColorDisputeStreak += 1;
            } else {
                driverState.lastColorDisputeKey = disputeKeyResolved;
                driverState.lastColorDisputeStreak = disputeKeyResolved ? 1 : 0;
            }
            if (disputeKeyResolved && driverState.lastColorDisputeStreak >= driverState.cfg.colorDisputeStreak) {
                const rawBoard = verifyChars.slice();
                for (let i = 0; i < disputed.length; i++) {
                    rawBoard[disputed[i]] = observed.rawChars[disputed[i]];
                }
                const rawVerdict = verifier.verify({
                    ticket: verifyTicket,
                    observed: rawBoard,
                    unknownCells: driverActions.countUnknownChars(rawBoard),
                    stable: true,
                    geometryTrusted: true,
                    conflicts: observed.conflicts,
                    predictedReplies: predictedForVerify,
                    // ⚠️ 必须带这个：验证器里还有一道「落点格被装饰画花 → 按票据改回」的修复
                    //   （`onlyDestination` 分支），不关掉它，原始读数会在里面**又被改回 C** ——
                    //   刚实现时真机复现过：复议照样返回「装饰容差下有 11 种应手解释，不唯一」。
                    trustDestReading: true,
                    // ★ 允许「对方吃在我方落点上」这个解释（同兵种反吃；见 verifier 里的说明）。
                    allowOwnDestCapture: true
                });
                const cellNames = disputed.length
                    ? disputed
                          .map(function (i) {
                              return (
                                  "(" +
                                  (i % 9) +
                                  "," +
                                  Math.floor(i / 9) +
                                  ")" +
                                  observed.rawChars[i] +
                                  "→" +
                                  verifyChars[i]
                              );
                          })
                          .join(" ")
                    : "无同字异色分歧";
                if (verifier.isSuccess(rawVerdict.result)) {
                    driverState.stats.colorDisputeAdopted += 1;
                    driverActions.note(
                        disputeMode +
                            "僵持 " +
                            driverState.lastColorDisputeStreak +
                            " 帧（" +
                            cellNames +
                            "）→ 按原始读数复议：" +
                            verifier.describe(rawVerdict)
                    );
                    verdict = rawVerdict;
                    // 下游跳过「应手两次佐证」：这个结论已经有**连续多帧**证据（就是那 N 帧僵持）。
                    verdict.disputeAdopted = true;
                    // 把**验证器改正过的那几格**钉住（本票之内以裁定值为准）
                    const pinned = [];
                    if (rawVerdict.board) {
                        for (let i = 0; i < 90; i++) {
                            if (rawVerdict.board[i] !== verifyChars[i]) {
                                pinned.push({ index: i, char: rawVerdict.board[i] });
                            }
                        }
                    }
                    if (pinned.length > 0) {
                        driverState.settledRaw = { ticketId: ticket.actionId, cells: pinned };
                    }
                    driverState.lastColorDisputeKey = "";
                    driverState.lastColorDisputeStreak = 0;
                } else if (driverState.lastColorDisputeStreak === driverState.cfg.colorDisputeStreak) {
                    driverActions.note(
                        disputeMode +
                            "僵持 " +
                            driverState.lastColorDisputeStreak +
                            " 帧（" +
                            cellNames +
                            "）→ 按原始读数复议仍无结论：" +
                            verifier.describe(rawVerdict) +
                            "（继续按修正后的盘面等下一帧）"
                    );
                }
            }
        } else {
            driverState.lastColorDisputeKey = "";
            driverState.lastColorDisputeStreak = 0;
        }
        driverActions.note("确认：" + verifier.describe(verdict));
        // ★ 「游戏没接受这一步」→ 记入本局面黑名单，下次规划不再选它。
        // 为什么必须有：引擎是确定性的，重规划会算出**同一手**；真机实测（K40，c2c6）点下去局面
        // 毫无变化 → 确认 UNCHANGED → 重试 1 次仍 UNCHANGED → RECOVERING → 重新规划又选中同一手
        // → 永远走不完一局。记黑名单后就会换次优着法继续下。
        if (verdict.result === verifier.RESULT.UNCHANGED) {
            driverActions.markMoveRejected(ticket, "确认未落子（局面与走之前一致）");
        }
        // 诊断（量很少，一手棋 2~3 行）：把实际喂给验证器的观测盘面与期望/基准都打出来。
        // 「确认判错」这类问题不看这三串就只能猜（真机踩过：观测其实是「我方落子+对方应手」，
        // 但合并/补齐改过的格子没记录下来，事后无法复盘）。
        if (!verifier.isSuccess(verdict.result)) {
            driverActions.note(
                "  确认观测：" +
                    driverActions.boardText(observed.chars) +
                    "｜期望：" +
                    (ticket.expectedAfterFen || "-") +
                    "｜基准：" +
                    (ticket.baseFen || "-")
            );
        }
        if (verifier.isSuccess(verdict.result)) {
            driverState.stats.confirms += 1;
            driverState.verifyCycle += 1;
            // ★ 我方这一步**真的走了** → 起点那一格从此是空的，它在补齐表里的记录必须作废。
            // ⚠️ 以前这里是「按周期把**整张表**作废」（`patch.cycle !== verifyCycle`）——
            // 真机事故（2026-09-14，K40，auto35.log）：上一步「对方刚落子」的格子登记在周期 N，
            // 我方这手一确认周期变 N+1 → 那条记录立刻失效 → 那个子带着高亮环被读成**空** →
            // 观测比期望只差这一格 → 装饰容差枚举出 8 种结构等价的解释（“那格子的炮走到别处、
            // 新位置没看见”）→「不唯一」→ 连刷 54 次、一手棋白等 54 秒、连第二手都走不下去。
            // 现在只丢**我方这手的起点**，其余记录留到它们真的失效（自己认出来了 / 有子从那里离开 /
            // 被换成异色子）—— 判断依据是“棋子动了”，不是“过了几手”。
            const from = transition.squareIndex(ticket.moveICCS, 0);
            if (from >= 0) {
                driverActions.dropPatchesAt([from], "我方这手 " + ticket.moveICCS + " 的起点已空");
            }
            // 这手已经写进（或即将写进）可靠基准 —— 补齐时不再按「在飞」处理：
            // 留着会把**下一手**的观测也按这一步走过之后的盘面去比（多走一步）。
            driverState.activeOwnMove = null;
        }
        // ★ 确认成功后**刷新可靠基准** —— ③ 连续走子必需的一环（K40 真机踩到）：
        // 不刷新的后果：基准还停在「我方落子之前」，而下一帧局面已经多了我方这手（以及对方应手）
        //   → 从旧基准看是「两手都变了」→ 观察层「唯一合法着法能解释」这条补不成立
        //   → 对方应手那格的高亮环补不出来（未知格≠0）→ `sideFor` 直接放弃 → 8s 看门狗
        //   → 永久 RECOVERING（真机日志：确认 CONFIRMED 之后就一直「轮次未知超时」）。
        // 刷成「确认后的局面」之后：对方应手只改 2 格，正好是 F2 能解释的情形 → 补得出来 → 轮次接得上。
        if (verifier.isSuccess(verdict.result) && verdict.board && verdict.board.indexOf("?") < 0) {
            const mySide = ticket.baseSide === "b" ? "b" : "w";
            // ★ 应手要跨帧佐证过才采信（见 replyCorroborated）：单独一帧枚举出来的应手
            //   可能是模型重复框伪影造的**假应手**，写进可靠盘面就会把整局钉死。
            const replyMove = verdict.plies && verdict.plies.length > 1 ? verdict.plies[1] : null;
            const replyKey = replyMove
                ? ticket.moveICCS +
                  ">" +
                  replyMove +
                  ">" +
                  (verdict.replyPatch ? verdict.replyPatch.index + ":" + verdict.replyPatch.char : "-")
                : null;
            // ★ 「同字异色复议」采信的应手自带连续多帧证据（僵持 ≥ `cfg.colorDisputeStreak` 帧），
            //   不再要求「两次佐证」—— 否则它永远进不了可靠基准，整局停在这一手上（真机实测）。
            const replyOk =
                replyMove == null || verdict.disputeAdopted === true ? true : driverActions.replyCorroborated(replyKey);
            replyRejected = replyMove != null && !replyOk;
            if (replyRejected) {
                driverActions.note(
                    "应手待佐证：这一帧枚举出应手 " +
                        replyMove +
                        "，等下一次验证再看到同一个应手才采信（防模型重复框伪影造出假应手）"
                );
            }
            const nextSide =
                verdict.result === verifier.RESULT.OPPONENT_REPLY && replyOk
                    ? mySide // 对方已应手 → 又轮到我方
                    : mySide === "w"
                      ? "b"
                      : "w"; // 只确认了我方落子（或应手未佐证）→ 轮到对方
            // 应手未佐证时：可靠盘面只取「我方的着法」，不把那一帧的应手带进去。
            const reliableBoard = replyOk || !expectedBoard ? verdict.board.slice() : expectedBoard.slice();
            driverState.lastReliable = { board: reliableBoard, side: nextSide };
            if (ticket && ticket.moveICCS) {
                driverState.lastMoves.own = { iccs: ticket.moveICCS, at: driverState.nowMono() };
            }
            // A3 影子：先把「我方这手」入账，再把已采信的应手入账（不一致只计数，不影响行为）
            const ledgerBase = expectedBoard ? driverActions.boardOfFen(verifyTicket.baseFen) : null;
            if (ledgerBase && expectedBoard) {
                driverActions.trackConfirm({
                    iccs: ticket.moveICCS,
                    fromBoard: ledgerBase,
                    side: ticket.baseSide,
                    afterBoard: expectedBoard,
                    source: "our-click"
                });
            }
            if (replyOk && replyMove && expectedBoard) {
                driverActions.trackConfirm({
                    iccs: replyMove,
                    fromBoard: expectedBoard,
                    side: mySide === "w" ? "b" : "w",
                    afterBoard: reliableBoard,
                    source: "opponent-observed",
                    toleranceIndexes: verdict.replyPatch ? [verdict.replyPatch.index] : []
                });
                // ★ 轻量预判记账：对方的应手在「确认帧」阶段被采信
                driverActions.noteOpponentMove(
                    replyMove,
                    "确认帧",
                    verifyTicket ? verifyTicket.expectedAfterFen : null
                );
                driverState.lastMoves.opponent = { iccs: replyMove, at: driverState.nowMono() };
            }
            driverState.decisionEpoch += 1; // 一手棋真正落定 → 新的回合世代（循环历史去重只在世代内生效）
            driverActions.note(
                "可靠基准已刷新（确认 " +
                    verdict.result +
                    (replyRejected ? "，应手未采信" : "") +
                    "）：" +
                    (nextSide === mySide ? "轮到我方" : "轮到对方")
            );
            // 确实取得了进展（棋真的变了）→ 才能清尝试预算。
            // 恢复成功、换新票据、换几何**都不算**进展，所以它们都不在这里。
            driverActions.clearAttemptBudget("已确认真实变化 " + verdict.result);
        }
        // ★ 把「落点格」登记进已知事实表（不管这次确认有没有用到它）。
        // 为什么要无条件登记：游戏会给我方刚落的子画高亮环，**下一帧起**那一格就认不出来了。
        // 如果只在「确认时刚好是未知格」才登记，就会出现：确认成功 → 下一帧那格变 '?' →
        // 未知格≠0 → `sideFor` 直接放弃 → 8s 看门狗 → 永久 RECOVERING
        // （真机实测：一手棋确认成功后原地空转 90 秒，没走第二手）。
        // 落点这一格是**已知事实**（票据 + 棋规推出来的），不是猜；等那格能自己认出来就自动失效。
        if (verifier.isSuccess(verdict.result) && expectedBoard) {
            let dest = null;
            try {
                dest = verifier.destinationIndex(ticket);
            } catch (error) {
                dest = null;
            }
            if (dest != null && expectedBoard[dest]) {
                driverActions.addPatch({ index: dest, char: expectedBoard[dest] }, "落点格（我方新落的子会被画环）");
            }
            // ★ 途经格也要登记（2026-09-19，K40 实测）：
            // 游戏会把走子**轨迹**画出来，那条线盖住的中间格会被模型读成 '?'，而且会一直留到
            // 下一步 → 观察层每帧差 1 个未知格 → `sideFor` 放弃 → 「轮次未知」空转 60 秒（重定位也没用）。
            // 途经格的内容是**棋规唯一的**（直线滑行 ⇒ 全空；炮吃子 ⇒ 中间恰有一枚炮架），
            // 期望盘面里就写着：有子就按子登记，空就登记成空网格。读到别的子时自动作废（见 registerPatch）。
            let pathSet = null;
            try {
                pathSet = verifier.pathIndexesBetween ? verifier.pathIndexesBetween(ticket) : null;
            } catch (error) {
                pathSet = null;
            }
            if (pathSet) {
                const pathKeys = Object.keys(pathSet);
                for (let i = 0; i < pathKeys.length; i++) {
                    const index = Number(pathKeys[i]);
                    if (expectedBoard[index]) {
                        driverActions.addPatch(
                            { index: index, char: expectedBoard[index] },
                            "途经格（游戏画的走子轨迹）"
                        );
                    } else {
                        driverActions.addEmptyPatch(index, "途经格（游戏画的走子轨迹，棋规上必为空）");
                    }
                }
            }
        }
        // 落点格被游戏画花（未知）→ 记下来，后续观察一律按已知事实补齐。
        // 我方落点与对方应手落点都是棋盘上的事实（都从票据 + 棋规唯一推出来），不是猜。
        // （addPatch 本体在工厂作用域，见 registerMoveDestinations 旁边 —— sideFor 也要用它。）
        if (verdict.patched) {
            driverActions.addPatch(verdict.patched, "落点格");
        }
        if (verdict.replyPatch && !replyRejected) {
            driverActions.addPatch(verdict.replyPatch, "对方应手落点格");
        }
        // 应手未得到佐证 → 把结论降级成「只确认了我方落子」，并且**不把那个应手**
        // （连同它的补齐记录）交给状态机/后续帧；下一帧还能再枚举一次，两次一致才采信。
        const finalVerdict = replyRejected
            ? Object.assign({}, verdict, {
                  result: verifier.RESULT.CONFIRMED,
                  replyPatch: null,
                  plies: [ticket.moveICCS],
                  reason: (verdict.reason || "") + "｜应手未佐证（等下一次验证再确认）"
              })
            : verdict;
        // ★ 已知事实被证伪（OCR 读到空格/别的子、或催了多次仍读不出来）：
        //   补齐表与可靠基准已经改对了，**并且期望值已经在上面按当前基准重建**（见 verifyTicket），
        //   所以验证可以照常走完 —— 走完才能把基准推进到「我方这手之后」。
        //   ⚠️ 早先这里是「作废票据、重新观察」，结果是**基准永远停在走之前**，
        //      下一帧就变成「既有合法单步也解释不了」（真机实测：卡在 WAIT_TURN 轮次未知）。
        //      期望值能重建之后这条就没必要了（2026-09-19 改）。
        if (driverState.factInvalidated) {
            driverState.factInvalidated = false;
            driverActions.note("（已知事实被证伪：补齐表与基准已改正，验证按重建后的期望值继续）");
        }
        // ★ 复议采信后（含本票后续每一帧）：把那几格的已知事实写成**裁定值**。
        //   必须放在本次确认所有补丁登记之后 —— 上面的「落点格」登记会按**票据里的旧期望值**
        //   把那一格又写成我方的子，那正是循环的燃料（真机实测：一期循环 49 次）。
        if (
            driverState.settledRaw &&
            driverState.settledRaw.ticketId === ticket.actionId &&
            verifier.isSuccess(verdict.result)
        ) {
            for (let i = 0; i < driverState.settledRaw.cells.length; i++) {
                const cell = driverState.settledRaw.cells[i];
                if (typeof cell.char === "string" && cell.char && cell.char !== "?") {
                    driverActions.registerPatch({ index: cell.index, char: cell.char }, "复议裁定（裁定值）");
                } else {
                    driverActions.dropPatchesAt([cell.index], "复议裁定：那格被判为空");
                }
            }
        }
        return driverActions.pump(
            driverState.machine.dispatch({ type: "VERIFY_RESULT", ticket: ticket, verdict: finalVerdict })
        );
    }

    function recover() {
        // 不论这次是否真的重定位，RECOVERING 都表示旧基准/已知事实已经无法解释当前画面。
        // 必须在“25 秒内跳过重定位”的早退之前置位，否则最常走的快速恢复永远不会重建基准。
        driverState.needFreshBaseline = true;
        // ⚠️ 重新定位一次 ~8s（K40 实测 7.6~8.2s），而**棋子移动不改变几何**。
        // 失败循环里每次都重新定位的话，一手棋要白烧十几秒 → 这里限频：
        // 距上次定位不足 RELOCATE_MIN_INTERVAL_MS 时只「重新武装」（直接 RECOVERED → 下一轮观察）。
        const now = driverState.nowMono();
        if (now < driverState.geometryStaleUntil) {
            // ★ 检测框报过「几何偏移超护栏」（棋子移动不会让原点差两格）——几何变了的强证据，
            //   不能走下面的「几何没变、直接重新观察」省时路（会永远拿错位置去读格子）。
            driverState.geometryStaleUntil = 0;
            driverActions.note("恢复：检测框已报几何明显偏移 → 跳过限频，强制重新定位");
        } else if (
            driverState.machine.geometryTrusted() &&
            driverState.lastRelocateMono > 0 &&
            now - driverState.lastRelocateMono < driverState.RELOCATE_MIN_INTERVAL_MS
        ) {
            driverActions.note(
                "恢复：距上次定位不到 " +
                    Math.round(driverState.RELOCATE_MIN_INTERVAL_MS / 1000) +
                    "s —— 几何没变，直接重新观察（省掉 ~8s 重定位）"
            );
            return driverActions.pump(driverState.machine.dispatch({ type: "RECOVERED" }));
        }
        driverState.lastRelocateMono = now;
        driverActions.note("恢复：重新定位棋盘");
        // 定位失败时不能在下一轮借限频跳过检查，重新启用旧坐标。
        driverState.machine.dispatch({ type: "GEOMETRY", trusted: false, reason: "等待重新定位结果" });
        // 恢复 = 这一手不再「在飞」（票据会作废/重发）：补齐时不能再按「走了一手」去比基准。
        // ⚠️ 但**票据还活着时不许清**（真机 2026-09-21 07:43 铁证）：确认 g0g3 期间一次
        //   「恢复：重新定位棋盘」把 activeOwnMove 清空 ⇒ 落点 (6,6) 失去保护，被幻影规则
        //   当成「多出来的那份」反复清 ⇒ 观测里我方那枚車永远不见 ⇒ 12~13 种解释的
        //   UNRELIABLE 死循环 + 后续崩溃。票据真作废（CANCEL/确认成功）时另有清理点。
        const liveTicket =
            typeof driverState.machine.currentTicket === "function" ? driverState.machine.currentTicket() : null;
        if (!liveTicket) {
            driverState.activeOwnMove = null;
        }
        // ⚠️ **不要**在这里清 lastReliable / 已知事实表（曾经清过，K40 实测直接死锁）：
        // recover 只是重新对齐几何，**轮次不是从当前帧推出来的、而是从上一步延续的**。
        // 一旦清掉，而局面已经不是标准开局（双方各走了一手），`sideFor` 就永远推不出来
        // → 永久 RECOVERING。保留基准反而更安全：`transition.infer` 会自己校验（一致=MOVE/
        // MOVE_PAIR，不一致=其它状态 → 仍然是「推不出」），不会拄着错盘面出手。
        // 补齐表里的格子是**棋盘格子下标**，与几何无关，换几何也一样有效。
        const located = driverState.opts.locate(driverActions.locateOptions());
        if (!located || !located.ok || !located.grid) {
            driverState.stats.errors += 1;
            driverState.stats.lastError = (located && located.reason) || "定位未返回有效棋盘坐标";
            driverActions.note("恢复时定位失败（继续等下一轮）：" + driverState.stats.lastError);
            return null; // 留在 RECOVERING，下一轮再试
        }
        driverState.grid = located.grid;
        driverActions.note("恢复完成，几何 v" + driverState.grid.version);
        return driverActions.pump(driverState.machine.dispatch({
            type: "RECOVERED", geometryTrusted: true, geometryVersion: driverState.grid.version
        }));
    }

    return { doVerify: doVerify, recover: recover };
}

module.exports = { create: create };
