// @engine quickjs
"use strict";

/** 盘面修正与重拍：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const rulesModule = require("../../chess/rules.js");
const PIECE_CHAR_RE = /^[rnbakcpRNBAKCP]$/;

function create(driverState, driverActions) {
    /**
     * 清掉「幻影 / 重复子」：同一枚子在观测里**占了两格**（棋子不会分身）。
     *
     * ★ 不变量（硬）：一局里任何一方的子数**只会减少或不变**（吃子少一枚；象棋没有升变/投放）。
     *   所以「观测里某枚子的个数 > 参照盘面里的个数」⇒ 多出来的那些格**一定**是错觉
     *   （模型的检测框偏了一格／重复框）。
     *
     * 判据（收得很紧）：
     *   · 参照盘面说那格是**空**（参照那边也有子 ⇒ 不是多出来的那格）；
     *   · 观测在那格有子 X，且观测里 X 的个数**多于**参照盘面；
     *   · 候选格数 == 多出来的份数（多一格都不清，宁可交给恢复流程）。
     *
     * 真机现场（2026-09-20，K40，now14 18:20:53）：模型把 (0,0) 那枚黑車的框偏到了 (1,0)
     * ⇒ 观测 `rrbakab1r/…` vs 期望 `r1bakab1r/…` ⇒ 确认连刷 5 次
     * 「还有 N 个未知格（且看得清的格子对不上这一手）」、轮次也推不出来 → 恢复（白烧 ~20s）。
     *
     * @param chars     观测盘面（会被**就地**修改）
     * @param reference 参照盘面（确认阶段＝票据的预期局面；其余时候＝上一张可靠基准）
     * @returns 清掉的格数
     */
    function clearPhantoms(chars, reference, why) {
        if (!chars || !reference) {
            return 0;
        }
        const obsCount = {};
        const refCount = {};
        const keepCount = {}; // 参照那格有子、观测也还是**同一个字**的份数（"原地没动"）
        for (let i = 0; i < 90; i++) {
            // ⚠️ 这里**不排除我方落点格**：预期那边落点是有子的、观测这边可能读成别的字，
            //   单边排除会把两边计数算成不平衡 ⇒ 凭空多出 1 枚 ⇒ 误清我方那枚子的**起点**
            //   （2026-09-20 自检直接挂住，就是这条）。落点保护只加在下面的清理动作上。
            const a = chars[i];
            // ⚠️ '?' 是「看不清」，**不是棋子**：既不参与计数，也不许被这里当成幻影清掉
            //   （把它清成空就是「拿看不清当没子」—— 本文件里已经有好几处教训了）。
            if (typeof a === "string" && a && a !== "?") {
                obsCount[a] = (obsCount[a] || 0) + 1;
            }
            const b = reference[i];
            if (typeof b === "string" && b && b !== "?") {
                refCount[b] = (refCount[b] || 0) + 1;
                if (a === b) {
                    keepCount[b] = (keepCount[b] || 0) + 1;
                }
            }
        }
        const spots = {};
        for (let i = 0; i < 90; i++) {
            const got = chars[i];
            if (!(typeof got === "string" && got && got !== "?")) {
                continue;
            }
            // 将/帅每方只有一枚，且旧格可能已被找色作废：参照盘面一旦暂时丢将，
            // 新落点就会被计数规则当作「多出来的份」清掉（K40 118 步 d9，真机实拍）。
            // 唯一将帅永远不能由幻影规则删除；重复/缺失交给规则门与恢复流程。
            if (got === "K" || got === "k") {
                continue;
            }
            // ⚠️ **我方落点格不许在这里被动**（2026-09-20 真机回归）：
            //   那一格的子是我方刚走过去的（带高亮环），被读错兵种/颜色是**已知模式**，
            //   由「票据预期 + 落点格补齐」那条专门的路救（verifier 的 decorated / 驱动的规则②）。
            //   幻影规则把它清成空之后，那条路反而接不上（空不是「装饰」）⇒ 直接 UNEXPECTED → 恢复。
            if (driverState.activeOwnMove && driverState.activeOwnMove.destIndex === i) {
                continue;
            }
            const want = reference[i];
            if (want != null && want !== "") {
                continue; // 参照那边这个格子也有子 ⇒ 不是「多出来的那格」
            }
            if ((obsCount[got] || 0) - (refCount[got] || 0) <= 0) {
                continue; // 这枚子的个数没变多 ⇒ 与幻影无关
            }
            if (!spots[got]) {
                spots[got] = [];
            }
            spots[got].push(i);
        }
        let cleared = 0;
        const notes = [];
        for (const ch in spots) {
            // ★★ 关键收紧（2026-09-20 真机死循环的根因）：
            //   只有当参照里这枚子的**每一份都还乖乖待在原位**时，「多出来的那一份」才只能是分身。
            //   反例（now20，卡了 19 次「轮次推不出来」）：我方炮(2,3) 被误读成黑卒 ⇒ 卒数 +1，
            //   而对方那枚卒**从 (6,3) 走到了 (6,4)** —— 参照在 (6,3) 的那份已经不在了，
            //   于是「多出来的份」其实对应「那枚卒移动了」，被误判成幻影清掉 ⇒ 盘面少了对方一枚卒
            //   ⇒ 任何合法着法都解释不了 ⇒ 恢复 → 重建基准 → 再撞，循环。
            //   移动过（keep < ref）就整类不动手，交给恢复流程。
            if ((keepCount[ch] || 0) !== (refCount[ch] || 0)) {
                continue;
            }
            const excess = (obsCount[ch] || 0) - (refCount[ch] || 0);
            if (spots[ch].length !== excess) {
                continue; // 多出来的份数与候选格数对不上 ⇒ 说不准是哪一格，交给恢复
            }
            for (let k = 0; k < spots[ch].length; k++) {
                const index = spots[ch][k];
                // ★ 清之前先问一句「那格真的是敌方那枚子吗？」——真机 now24：(4,2) 的黑炮被连清 **8 次**，
                //   其实是我方子被高亮环读成了敌方兵种（与 (2,3)/(8,5) 同一类误读，只是那两格有
                //   已知事实记录、这格没有）。像素 + OCR 的阵营与观测**相反** ⇒ 说不准，
                //   **不清也不猜**（改成我方那只子会立刻又凑出我方那份的重复计数，只是换一出戏）；
                //   留给恢复流程（它会按整帧重新建基准，几分钟内自愈），但绝不删掉可能是真子的那枚。
                const ev = driverActions.cellEvidence(index);
                const factSide = ch === ch.toUpperCase() ? "RED" : "BLACK";
                const where = "(" + (index % 9) + "," + Math.floor(index / 9) + ")";
                if (ev && ev.hasPiece !== false && (ev.side === "RED" || ev.side === "BLACK") && ev.side !== factSide) {
                    driverState.stats.phantomHeldBack = (driverState.stats.phantomHeldBack || 0) + 1;
                    driverActions.note(
                        "幻影候选没敢清：" +
                            where +
                            ch +
                            "，但像素（找色）说那格是" +
                            (ev.side === "RED" ? "红" : "黑") +
                            "（多半是我方子被读成敌色）→ 交给恢复流程"
                    );
                    continue;
                }
                // ★ 2026-09-20 晚（真机 now48：`(5,3)r→空` 每帧循环 20+ 次）：
                //   同一格连着 `cfg.phantomAdoptStreak` 帧都「多出来」、而且**那格真有墨迹**，
                //   那就不是误读 —— 是**可靠基准漏收了一枚子**（对方的子走过来了，但那一手没被
                //   推断采纳；基准里这枚子的另一份还乖乖在原位 ⇒ 计数恒定“多一份”）。
                //   每帧清掉它只会让观测永远对不上真棋盘，且这枚子永远进不了基准（它的走子永远推不出来）。
                //   僵持到点就**收编**：写进基准（计数对上），而不是继续清。
                //   ⚠️ 两条保护（都踩过）：
                //     · 证据说那格没墨迹（ev.hasPiece === false）⇒ 模型框漂移的假子 ⇒ 照旧清、绝不清账；
                //     · **收编要正证据**：决定那一帧必须有「那格真有墨迹」（ev.hasPiece === true）。
                //       没有证据的环境（离线夹具 / Rhino）一律维持旧行为 —— 18:20 那个框漂移回归
                //       夹具就是靠这条保护的（它没有 ocrCell 证据，照旧清）。
                if (driverState.lastReliable && reference === driverState.lastReliable.board) {
                    const hasInk = !!(ev && ev.hasPiece === true);
                    const noInk = !!(ev && ev.hasPiece === false);
                    if (noInk) {
                        delete driverState.phantomStreak[index]; // 假子：照旧清
                    } else {
                        const key = index + ":" + ch;
                        const prev =
                            driverState.phantomStreak[index] && driverState.phantomStreak[index].key === key
                                ? driverState.phantomStreak[index].count
                                : 0;
                        if (prev + 1 >= driverState.cfg.phantomAdoptStreak && hasInk) {
                            delete driverState.phantomStreak[index];
                            // ★ 收编也是「改基准」：先登记旧基准（它可能是对方应手的落点，也可能不是 ——
                            //   是不是，交给 sideFor 的差分追踪对账，见 recordBaselineRevision）
                            driverActions.recordBaselineRevision("幻影收编 " + where + ch);
                            const adopted = driverState.lastReliable.board.slice();
                            adopted[index] = ch;
                            driverState.lastReliable = { board: adopted, side: driverState.lastReliable.side };
                            driverState.stats.phantomAdopted = (driverState.stats.phantomAdopted || 0) + 1;
                            driverActions.note(
                                "幻影僵持 " +
                                    (prev + 1) +
                                    " 帧（" +
                                    where +
                                    ch +
                                    " 连着多出来、且那格真有墨迹）→ 判为可靠基准漏收这枚子，收编进基准"
                            );
                            continue; // 不清：这是真子
                        }
                        driverState.phantomStreak[index] = { key: key, count: prev + 1 };
                    }
                }
                // ★ 2026-09-24（K40 实拍：用户「自己被将军了 还在走其他的」的根因）——
                //   对方那枚**贴着我方帅将军的黑车**（观测 (3,9)r；它刚从别处走到底线）被这里
                //   当成「重复子」**连着清了 7 分钟**（日志：`清掉幻影（重复子）1 格（(3,9)r→空）`
                //   每帧一次）—— 驱动眼里的盘面**永远没有将军** ⇒ 引擎一直走别的
                //   （真实棋局里全都不合法，游戏全部拒收）⇒ 面板卡在「轮次推不出来/RECOVERING」，
                //   用户看着干着急。为什么既有护栏没拦住：收编（phantomAdoptStreak）要求
                //   「那格真有墨迹」的证据，而单格证据对那一格一直给不出/给的是空（限频 +
                //   黑车细笔画密度压线）⇒ 永远走「照旧清」。
                //   这里补的是**不依赖证据的硬判据**：清一枚子**不许改变我方帅的被将状态**
                //   （含将帅照面）—— 拿掉前后不一样 ⇒ 它可能就是将军的矛（或挡将的盾）⇒
                //   不清，留给恢复/收编流程。宁可多花几秒，也不能把「正在被将」从盘面上删掉。
                const myRedSide = driverState.machine.mySide() !== "b";
                const checkBefore = rulesModule.isInCheck(chars, myRedSide);
                chars[index] = null;
                const checkAfter = rulesModule.isInCheck(chars, myRedSide);
                chars[index] = ch;
                if (checkBefore !== checkAfter) {
                    driverState.stats.phantomHeldByCheck += 1;
                    driverActions.note(
                        "幻影候选没敢清：" +
                            where +
                            ch +
                            "—— 拿掉它会" +
                            (checkBefore ? "解除" : "造成") +
                            "我方被将（将军的矛 / 挡将的盾都可能就是它）→ 留给恢复流程"
                    );
                    continue;
                }
                chars[index] = null;
                cleared += 1;
                if (notes.length < 4) {
                    notes.push(where + ch + "→空");
                }
            }
        }
        // ★ 僵持表只认「连续」：这一轮没有再作为候选出现的格子，把它的记录清掉。
        const seenNow = {};
        for (const ch2 in spots) {
            for (let k = 0; k < spots[ch2].length; k++) {
                seenNow[spots[ch2][k]] = true;
            }
        }
        for (const idx in driverState.phantomStreak) {
            if (!seenNow[idx]) {
                delete driverState.phantomStreak[idx];
            }
        }
        if (cleared > 0) {
            driverState.stats.phantomCleared = (driverState.stats.phantomCleared || 0) + cleared;
            driverActions.note(
                "清掉幻影（重复子）" +
                    cleared +
                    " 格（" +
                    notes.join("；") +
                    "）——" +
                    (why || "同一枚子在观测里占了两格") +
                    "；棋子不会分身"
            );
        }
        return cleared;
    }

    /**
     * 已知事实表里那一格的字（不查可靠基准）。
     * 用途：颜色翻转的硬闸（见 applyOcrBoardFixes）—— 已知事实是「票据 + 棋规唯一推出来」的，
     * 它说这格是我方炮，那它就是我方炮，不允许 OCR/像素把它翻成黑炮。
     * 返回 null 表示那格没有已知事实（空子的记录不算：子走了记录就该失效，颜色得重新看证据）。
     */
    function knownFactCharAt(index) {
        return driverState.knownFacts.charAt(index);
    }

    /**
     * 用**全盘 OCR**改正「同色不同兵种」的误读（模型把炮认成兵/卒那类）。
     *
     * 为什么必要时（2026-09-19，真机实拍）：模型把 (2,3) 的我方炮读成红兵、(4,6) 的黑炮读成黑卒 ——
     * 同色、只错兵种，于是驱动手里“黑方只剩一枚炮”、我方那枚炮变成兵，轮次推断永远解释不了。
     * 而 OCR 读的是**字形**（车马炮砲士相象帅将仕兵卒），那两格它读得清清楚楚。
     *
     * 只改三种情形（都是有证据的，不是猜）：
     *   ① 两边**同色**但兵种不同 ⇒ 取 OCR 的字形（这就是模型那个已知弱点）
     *   ② 模型空、OCR 有字 ⇒ 补上（漏检）
     *   ③ 模型有字、OCR 没读到 ⇒ **不动**（OCR 会被悬浮窗/水印挡佳，不能拿“没读到”去清子）
     */
    function applyOcrBoardFixes(chars, result) {
        if (typeof driverState.opts.ocrBoard !== "function" || !chars) {
            return 0;
        }
        const at = driverState.nowMono();
        if (driverState.lastOcrBoardAt > 0 && at - driverState.lastOcrBoardAt < driverState.cfg.ocrBoardCooldownMs) {
            return 0;
        }
        driverState.lastOcrBoardAt = at;
        let board = null;
        try {
            board = driverState.opts.ocrBoard();
        } catch (error) {
            board = null;
        }
        driverState.stats.ocrBoardChecks += 1;
        if (!board || !board.ok || !board.chars) {
            // ⚠️ 失败也要留痕：不然「没调用」「调用了但不可用」「调用了但无可改正」在日志里长得一样
            //   （2026-09-19 为此多跑了一轮真机）。
            driverActions.note("全盘 OCR 不可用：" + ((board && board.reason) || "没有返回值"));
            return 0;
        }
        let fixed = 0;
        let baselineFixed = 0;
        /** 等「连续帧一致」的格数 / 被分数、颜色、非法字形驳回的格数 */
        let pending = 0;
        let blocked = 0;
        const notes = [];
        const pendingNotes = [];
        const flipBlockedNotes = [];
        // 帧标识：面板给了 `at`（命中缓存时它不变）就用它；没给（自检/夹具）用「本次询问」当一帧
        driverState.frameConsensus.beginFrame(board.at);
        /**
         * 「同一格连续 N 帧说同一个字」闸门（实现见 frame-consensus.js：
         * 同帧重复问不刷计数；矛盾票标记 unstable 并重置）。
         */
        const consensus = function (index, ch, needed) {
            return driverState.frameConsensus.vote(index, ch, needed);
        };
        /** 同步改正可靠基准那一格（OCR 是字形证据；基准错了的话，票据期望也跟着错） */
        const fixBaseline = function (index, ch) {
            if (
                !driverState.lastReliable ||
                !driverState.lastReliable.board ||
                driverState.lastReliable.board[index] === ch
            ) {
                return;
            }
            const board2 = driverState.lastReliable.board.slice();
            board2[index] = ch;
            driverState.lastReliable = { board: board2, side: driverState.lastReliable.side };
            driverActions.trackFix(board2, driverState.lastReliable.side, { reason: "ocr-fix" });
            baselineFixed += 1;
        };
        for (let i = 0; i < 90; i++) {
            const mine = chars[i] && chars[i] !== "?" ? String(chars[i]) : null;
            const raw = board.chars[i] ? String(board.chars[i]) : null;
            // ① 不许把 `?` / 非法字形写进盘面与基准（2026-09-20：账本漂移的直接来源之一）
            const theirs = raw && PIECE_CHAR_RE.test(raw) ? raw : null;
            if (!theirs) {
                if (raw) {
                    blocked += 1; // OCR 回了 `?`/垃圾字：不动，只计数
                }
                continue; // 没读到 ⇒ 不动（会被悬浮窗/水印挡住，不能拿「没读到」去清子）
            }
            // ② 分数闸门：低分字形只记录、不采纳（面板按 minScore 滤过一道，这是第二道）
            const score = board.scores && board.scores[i] != null ? Number(board.scores[i]) : null;
            if (score != null && !(score >= driverState.cfg.ocrPatchMinScore)) {
                blocked += 1;
                continue;
            }
            // 兵种与颜色分开看：兵种取字形、颜色取**像素**（board-scan 已经把两者合成进 chars[i]）
            const missing = mine == null; // ① 模型漏检、OCR 看到了字 ⇒ 补上
            const classDiffers = !missing && mine.toLowerCase() !== theirs.toLowerCase();
            const colourDiffers = !missing && !classDiffers && mine !== theirs;
            if (!missing && !classDiffers && !colourDiffers) {
                continue; // 完全一致：没什么要改的
            }
            // ⚠️ 这两个 const 必须先声明：下面的「我方刚落的子」早退块要用（真机 now22 铁证：
            //    声明在后面 ⇒ 一命中就抛 `from is not initialized`（TDZ）⇒ `applyOcrBoardFixes`
            //    整体抛出 ⇒ 整轮作废 ⇒ 那格的误读留到下一帧 ⇒ 又「轮次推不出来」⇒ 恢复 —— 死循环）。
            const want = theirs;
            const from = missing ? "空" : mine;
            // ★ 往**空位**补子必须有正面墨迹证据（真机 now29 坏轮，K40）：OCR 把「炮」补到空格
            //   (4,7) 上（那格其实啥也没有）⇒ 与 (7,7) 的真炮来回打架（`(4,7)空→C (7,7)?→空`）⇒
            //   任何合法着法都解释不了 ⇒ 一轮刷 15 次「推不出来」。找色说那格没墨迹就一律驳回。
            if (missing) {
                const ink = driverActions.cellEvidence(i);
                if (ink && ink.hasPiece === false) {
                    blocked += 1;
                    driverState.stats.ocrEmptyBlocked = (driverState.stats.ocrEmptyBlocked || 0) + 1;
                    if (flipBlockedNotes.length < 4) {
                        flipBlockedNotes.push(
                            "(" + (i % 9) + "," + Math.floor(i / 9) + ")空→" + want + "（找色说那格没墨迹 ⇒ 驳回）"
                        );
                    }
                    continue;
                }
            }
            // ★ 我方**正要落到**的那一格：OCR 的任何改正都不许动它（2026-09-20 真机第二次踩到）：
            //   点击发出 → 确认成功之间，落点格带着高亮环，模型/像素一会儿说它是黑炮、一会儿说是卒
            //   （日志 17:54:52 落子 → 17:54:55 `(2,3)C→c`；17:55:12 基准直接被写成 `p`）。
            //   可那一格是什么子**驱动自己就知道**：就是起点那枚子（观察层在点击窗口里已经按它补齐）。
            if (driverState.activeOwnMove && driverState.activeOwnMove.destIndex === i) {
                blocked += 1;
                driverState.stats.ocrFlipBlocked = (driverState.stats.ocrFlipBlocked || 0) + 1;
                if (flipBlockedNotes.length < 4) {
                    flipBlockedNotes.push(
                        "(" + (i % 9) + "," + Math.floor(i / 9) + ")" + from + "→" + want + "（我方刚落的子）"
                    );
                }
                continue;
            }
            const flip = !missing && (mine === mine.toUpperCase()) !== (want === want.toUpperCase());
            // ★ 颜色翻转的**硬闸**（2026-09-20 真机铁证）：棋子一局里**不会改颜色**，
            //   所以「同一格、兵种没变、只是像素说是另一个颜色」时，颜色早就由**已知事实**定死了——
            //   再拿 OCR+像素去把它翻过来就是改坏账本。
            //   现场（日志 17:49:10~33）：我方刚落下的炮 (2,2)（落点带高亮环，像素判成黑）
            //   ⇒ 三帧共识把基准写成黑炮 ⇒ 之后每一帧都对不上、白烧一次恢复。
            //   注意闸门只看**已知事实表**（票据/棋规推出来的那些格），不看可靠基准：
            //   基准是“上一手的样子”，而「有子刚从别处走到这一格」时基准当然还是旧值。
            if (flip) {
                const settledFact = knownFactCharAt(i);
                if (settledFact) {
                    blocked += 1;
                    driverState.stats.ocrFlipBlocked = (driverState.stats.ocrFlipBlocked || 0) + 1;
                    if (flipBlockedNotes.length < 4) {
                        flipBlockedNotes.push(
                            "(" +
                                (i % 9) +
                                "," +
                                Math.floor(i / 9) +
                                ")" +
                                from +
                                "→" +
                                want +
                                "（已知事实是 " +
                                settledFact +
                                "）"
                        );
                    }
                    continue;
                }
            }
            // ③ 与像素（找色）结论不冲突：翻颜色时必须由像素给出**决定性**结论，否则不动
            if (flip) {
                const side = board.sides ? board.sides[i] : null;
                const wantRed = want === want.toUpperCase();
                if ((side !== "RED" && side !== "BLACK") || (side === "RED") !== wantRed) {
                    blocked += 1;
                    continue;
                }
            }
            const needed = flip ? driverState.cfg.ocrPatchFlipFrames : driverState.cfg.ocrPatchFrames;
            /**
             * ① **本帧观测**立即按证据改：它只活这一帧，专给「同帧的验证/轮次推断」用
             *    （不然模型把 (4,4) 炮读成卒这种格会把整手的确认拖成 UNEXPECTED）。
             *
             * ⚠️ **翻色是例外**（2026-09-20 真机 now39，白烧 25% 的整轮时间）：
             *   现场 (4,6) 那枚棋被游戏画了高亮环 ⇒ 模型逐帧在 c / C / ? 之间跳，而全盘 OCR 的
             *   像素颜色**也跟着跳**（先 `c→C（第 1/3 帧）`，一会儿又 `C→c（第 1/3 帧）`）。
             *   老代码把「本帧立即改」也用在翻色上 ⇒ 我们自己每帧再帮着翻一次 ⇒ 差分每帧都看到
             *   「变了」⇒ 任何着法都解释不了 ⇒ 每 ~5s 一次「轮次推不出来 → 恢复」。
             *   翻色本来就写着「要 3 帧共识」，那就**本帧也一起等共识**（与基准一致时照旧立即生效）。
             *   兵种改正（炮↔卒）与补子不受影响 —— 它们才是「同帧验证」真正需要的。
             */
            const agreesWithBaseline = !!(
                driverState.lastReliable &&
                driverState.lastReliable.board &&
                driverState.lastReliable.board[i] === want
            );
            const gate = agreesWithBaseline
                ? { ok: true, frames: needed, needed: needed, unstable: false }
                : consensus(i, want, needed);
            if (flip && !gate.ok && gate.unstable) {
                // 本帧**不改**：把这一格留给差分当「没变」，等连续两帧同票再说
                driverState.stats.colorFlipHeld = (driverState.stats.colorFlipHeld || 0) + 1;
            } else {
                chars[i] = want;
                driverState.evidenceIndexes.push(i); // 证据格：兵种上限体检不许回滚它
            }
            fixed += 1;
            notes.push(
                "(" +
                    (i % 9) +
                    "," +
                    Math.floor(i / 9) +
                    ")" +
                    from +
                    "→" +
                    want +
                    "(OCR " +
                    (board.glyphs[i] || theirs) +
                    ")"
            );
            // ② **基准 / 已知事实**要多帧共识（那才是账本，写进去就跨帧生效）
            /**
             * ★ 「OCR 说的与**上一张可靠基准**完全一致（兵种 + 颜色）」⇒ 不必等共识。
             *
             * 共识闸门防的是「拿一帧 OCR 的读数去改账本」；可这里 OCR 说的**正是账本上已有的值**，
             * 它要改的只是「这一帧读错的那一格」—— 那不是新事实，是把帧纠正回来。
             *
             * 真机（2026-09-20，K40，日志 18:15:40~53）：我方炮那格被高亮环读成黑卒 `p`，而全盘 OCR
             * 每 6s 才跑一次 ⇒ 「翻色要 3 帧」≈ 18s 才落地；可轮次看门狗 4s 就先触发了 ⇒
             * 每帧都「轮次推不出来」→ 恢复（白烧十几秒），一天里反复出现。
             */
            if (!gate.ok) {
                pending += 1;
                if (pendingNotes.length < 4) {
                    pendingNotes.push(
                        "(" +
                            (i % 9) +
                            "," +
                            Math.floor(i / 9) +
                            ")" +
                            from +
                            "→" +
                            want +
                            "（第 " +
                            gate.frames +
                            "/" +
                            gate.needed +
                            " 帧）"
                    );
                }
                continue;
            }
            // ★ 登记成已知事实：OCR 一次要 1.2s，限频下总有不跑 OCR 的帧，
            //   那些帧会把这一格读回原样 ⇒ 基准与观测在两套盘面之间来回跳，永远解释不通
            //   （2026-09-19 真机踩到）。登记后每帧都按这条事实补 ✔
            driverActions.registerPatch(
                { index: i, char: want },
                missing
                    ? "全盘 OCR 证据（模型漏检）"
                    : classDiffers
                      ? "全盘 OCR 证据（兵种取字形）"
                      : "全盘 OCR 证据（颜色取像素）"
            );
            fixBaseline(i, want);
        }
        if (fixed > 0) {
            driverState.stats.ocrBoardFixes += fixed;
            // 基准也被改过 ⇒ 标记一下：下次验证会用「当前基准 + 我方这手」重建票据的盘面
            // （否则票据里那张旧期望值会把这两个改正当成「无法解释的局面」）。
            if (baselineFixed > 0) {
                driverState.baselineCorrectedAt = driverState.nowMono();
                notes.push("基准改正 " + baselineFixed + " 格");
            }
            driverActions.note(
                "全盘 OCR 改正 " +
                    fixed +
                    " 格：" +
                    notes.slice(0, 6).join("；") +
                    (notes.length > 6 ? " …" : "") +
                    "（OCR 读到 " +
                    board.hits +
                    " 格有字）"
            );
            if (result) {
                result.ocrBoardFixes = fixed;
            }
        } else if (at - driverState.lastOcrBoardNoteAt > 30000) {
            driverState.lastOcrBoardNoteAt = at;
            driverActions.note("全盘 OCR 对账：无可改正（OCR 读到 " + board.hits + " 格有字）");
        }
        driverState.stats.ocrBoardPending += pending;
        driverState.stats.ocrBoardBlocked += blocked;
        // 待定/驳回也要留痕，否则「没调用」「调了但无可改正」「调了但全被判待定」在日志里长得一样
        const gateText =
            "全盘 OCR 待定 " +
            pending +
            " 格／驳回 " +
            blocked +
            " 格（本帧已用，连续 " +
            driverState.cfg.ocrPatchFrames +
            " 帧一致才写基准，翻色要 " +
            driverState.cfg.ocrPatchFlipFrames +
            " 帧）" +
            (pendingNotes.length ? "：" + pendingNotes.join("；") : "") +
            (flipBlockedNotes.length ? "｜颜色翻转被已知事实驳回：" + flipBlockedNotes.join("；") : "");
        if (
            (pending > 0 || blocked > 0) &&
            (gateText !== driverState.lastOcrGateText || at - driverState.lastOcrGateNoteAt > 30000)
        ) {
            driverState.lastOcrGateText = gateText;
            driverState.lastOcrGateNoteAt = at;
            driverActions.note(gateText);
        }
        return fixed;
    }

    /**
     * 重拍/直接补上都算「这格被证实了」⇒ 连空计数与催游戏次数都清零。
     * ⚠️ 不清零的后果（2026-09-19 自己踩的）：第 2 次催游戏（最近任务切出再切回）之后
     * 棋子真的重新画出来了、单格重拍也找回了那一格，但计数还在涨 ⇒ 又催了第 3 次，
     * 把一条**真实**的已知事实当假的丢掉，还把可靠基准一起改错。
     */
    function resetStuckStreak(patch) {
        if (!patch) {
            return;
        }
        patch.emptyStreak = 0;
        patch.nudges = 0;
        if (patch.index != null) {
            delete driverState.stuckNudgeAt[patch.index];
        }
    }

    function reprobeSuspects(suspects, result) {
        if (!suspects || suspects.length === 0 || !result || !result.chars) {
            return 0;
        }
        if (driverState.cfg.reprobeConfidence == null || driverState.cfg.reprobeConfidence <= 0) {
            return 0;
        }
        const now = driverState.nowMono();
        const pending = [];
        for (let i = 0; i < suspects.length; i++) {
            const lastAt = driverState.lastReprobeAt[suspects[i].index];
            if (lastAt != null && now - lastAt < driverState.cfg.reprobeCooldownMs) {
                continue;
            }
            pending.push(suspects[i]);
        }
        if (pending.length === 0) {
            return 0;
        }
        let probe = null;
        try {
            probe = driverState.opts.recognize({
                grid: driverState.grid,
                orientation: driverState.cfg.orientation,
                confidence: driverState.cfg.reprobeConfidence,
                minScore: driverState.cfg.reprobeMinScore,
                timeoutMs: 1200
            });
        } catch (error) {
            probe = null;
        }
        driverState.stats.reprobes += 1;
        for (let i = 0; i < pending.length; i++) {
            driverState.lastReprobeAt[pending[i].index] = now;
        }
        if (!probe || !probe.ok || !probe.chars) {
            return 0;
        }
        let filled = 0;
        for (let i = 0; i < pending.length; i++) {
            const patch = pending[i];
            const got = probe.chars[patch.index];
            if (got === "?") {
                // 有框但认不出 ⇒ 那格**不是空的**，已知事实说了是什么就补回去
                result.chars[patch.index] = patch.char;
                filled += 1;
                resetStuckStreak(patch);
                continue;
            }
            if (typeof got !== "string" || !/^[A-Za-z]$/.test(got)) {
                continue; // 重拍也说空 ⇒ 维持未知，下一轮再试
            }
            const sameColor = (got === got.toUpperCase()) === (patch.char === patch.char.toUpperCase());
            if (!sameColor) {
                // 不同色 ⇒ 已知事实可能已过期（子真被吃了）⇒ 不动，交给后续观察/恢复
                continue;
            }
            result.chars[patch.index] = got;
            filled += 1;
            resetStuckStreak(patch);
            driverState.evidenceIndexes.push(patch.index); // 降阈值重拍读出来的字：体检不回滚
        }
        if (filled > 0) {
            driverState.stats.reprobeFilled += filled;
            driverActions.note(
                "单格重拍找回 " +
                    filled +
                    " 格（降阈值重认：" +
                    pending
                        .map(function (p) {
                            return "(" + (p.index % 9) + "," + Math.floor(p.index / 9) + ")";
                        })
                        .join(" ") +
                    "）"
            );
        } else {
            // ⚠️ 失败也要留痕：不然「没触发」与「触发了但没找回」在日志里长得一模一样
            //（第一版就是这么被误导的：跑了 4 轮都以为没接上）。
            const reads = [];
            for (let i = 0; i < pending.length; i++) {
                const one = probe.chars[pending[i].index];
                reads.push(
                    "(" +
                        (pending[i].index % 9) +
                        "," +
                        Math.floor(pending[i].index / 9) +
                        ")=" +
                        (one === "?" ? "?" : one || "空") +
                        "（已知事实 " +
                        pending[i].char +
                        "）"
                );
            }
            driverActions.note("单格重拍没找回（降阈值重认仍为 " + reads.join(" ") + "）");
        }
        return filled;
    }

    return {
        clearPhantoms: clearPhantoms,
        knownFactCharAt: knownFactCharAt,
        applyOcrBoardFixes: applyOcrBoardFixes,
        resetStuckStreak: resetStuckStreak,
        reprobeSuspects: reprobeSuspects
    };
}

module.exports = { create: create };
