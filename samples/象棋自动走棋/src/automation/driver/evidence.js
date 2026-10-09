// @engine quickjs
"use strict";

/** 单格证据与一致性核对：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const verifier = require("../move-verifier.js");

function create(driverState, driverActions) {
    /**
     * 单格重拍（2026-09-19，K40 实测卡点）。
     *
     * 场景：已知事实表说「这格是子 X」（票据 + 棋规唯一推出来的），模型却**整格读成空**。
     * 这不是「子走了」的强证据 —— 整格读成空意味着**检测框都没出来**，而模型本身只有 mAP50 0.53。
     * 后果：每帧差 1 个未知格 → `sideFor` 数不齐子力 → 「轮次未知」→ RECOVERING 空转 60 秒
     * （真机：对方刚落下的黑炮，连续几十帧都是空）。
     *
     * 做法：**换一帧、按最低档检测阈值**重认一遍，但**只采信可疑格**的读数，其余格一概忽略。
     * 采信规则两条，都不能凭空造子：
     *   · 重拍读到字母：必须与已知事实**同色**（票据说这格是谁的子，就还是谁的）；
     *   · 重拍读到 '?'（有框但认不出）：说明那格确实有东西 ⇒ 按已知事实的字补回去。
     * 仍然读不到就作罢（下一轮还会再试），**绝不用已知事实直接盖章** ——
     * 那正是 2026-09-14 的事故：把真的走掉的子补回来 → 整帧看起来没变 → 永久 UNCHANGED。
     *
     * @param suspects [{index, char}] 且 char 必须非空（空网格不走这里）
     */
    /**
     * 单格 OCR 结论（限频：同一格 `cfg.ocrCooldownMs` 内只问一次）。
     *
     * 为什么要有它（2026-09-19，K40）：模型对某些格子读不出来（一会儿 '?'、一会儿整格空），
     * 只能靠补齐表猜；而 OCR 读的是**字形**，能直接给出「那格到底有没有子」的证据。
     * 返回 state：
     *   · "match"   OCR 读到的字与已知事实一致（同字或同色）→ 那格确实有子
     *   · "empty"   OCR 没读到任何字 → 那格是空的（不是模型漏检）
     *   · "other"   OCR 读到的是别的子 → 已知事实过期/记错了
     *   · "unknown" OCR 不可用或这次没问 → 退回「猜」的策略（催游戏等）
     */
    /**
     * 「即将丢掉一条已知事实」时的最后一道证据：问一次 OCR（优先单格 `ocrCell`，退而全盘 `ocrBoard`）。
     *
     * 只比**字形类别**（`.toLowerCase()` 相等）：OCR 只可信到兵种，颜色以已知事实为准
     * （与 `ocrCheckPatch` 的口径一致）。拿不到证据返回 null —— 那就照旧丢记录。
     */
    function ocrCellEvidence(index, char) {
        if (typeof driverState.opts.ocrCell === "function") {
            try {
                const one = driverState.opts.ocrCell({ index: index, char: char });
                if (one && one.ok && (one.cls || one.char)) {
                    return String(one.cls || one.char);
                }
            } catch (error) {
                /* 退到全盘 OCR */
            }
        }
        if (typeof driverState.opts.ocrBoard === "function") {
            try {
                const board = driverState.opts.ocrBoard();
                if (board && board.ok && board.chars) {
                    const got = board.chars[index];
                    if (typeof got === "string" && got && got !== "?") {
                        return got;
                    }
                }
            } catch (error) {
                /* 当没有证据 */
            }
        }
        return null;
    }

    /**
     * 问一次「那一格是**哪一边**」（像素颜色，配单格 OCR 一起用）—— 用户提议 2026-09-20：
     * 「YOLO 只定位一次，剩下的用找色判走动、OCR 认子」。这里是它的最小落地：
     * 遇到「同字异色」这种最危险的歧义时，拿**像素证据**代替猜。
     *
     * 返回 { side: "RED"|"BLACK", cls } 或 null（拿不到证据就不做判断）。
     * 限频：同一格 `cfg.ocrCooldownMs` 内只问一次（一帧多问几次会把截图/OCR 打满）。
     */
    function cellSideEvidence(patch) {
        if (typeof driverState.opts.ocrCell !== "function") {
            return null;
        }
        const at = driverState.nowMono();
        if (patch.sideAt && at - patch.sideAt < driverState.cfg.ocrCooldownMs) {
            return null;
        }
        patch.sideAt = at;
        let one = null;
        try {
            one = driverState.opts.ocrCell({ index: patch.index, char: patch.char });
        } catch (error) {
            one = null;
        }
        driverState.stats.ocrChecks += 1;
        if (!one || !one.ok || (one.side !== "RED" && one.side !== "BLACK")) {
            return null;
        }
        return { side: one.side, cls: one.cls || null, score: one.score };
    }

    /**
     * 一格的「颜色 + OCR」证据（用户提议 2026-09-20：**棋子定位用找色扫描，不再只靠模型**）。
     * 返回：
     *   · hasPiece：找色两边的墨迹密度都低于门槛 ⇒ false（空格）；至少一边有墨迹 ⇒ true；拿不到 ⇒ null
     *   · side：RED / BLACK（像素判阵营）
     *   · char：'C' / 'c' 这种完整棋子字符（OCR 认出兵种 + 像素定阵营才有）
     * 拿不到证据返回 null —— 调用方别据此下结论。
     * 限频：同一格 `cfg.ocrCooldownMs` 内只问一次（一次 = 抓一帧 + 一次 OCR + 两次找色）。
     */
    function cellEvidence(index) {
        if (typeof driverState.opts.ocrCell !== "function") {
            return null;
        }
        const at = driverState.nowMono();
        if (driverState.evidenceAt[index] && at - driverState.evidenceAt[index] < driverState.cfg.ocrCooldownMs) {
            return null;
        }
        driverState.evidenceAt[index] = at;
        let one = null;
        try {
            one = driverState.opts.ocrCell({ index: index });
        } catch (error) {
            one = null;
        }
        driverState.stats.ocrChecks += 1;
        if (!one) {
            return null;
        }
        // 空/有子：按找色密度（实测：有子的格 0.08~0.16，空格两边都 ≈0）；
        // 暗墨迹是第三路（2026-09-21 d7 黑士案：细笔画黑子的找色密度会压 6% 线、偏裁剪掉到 5.8%，
        // 而暗像素口径 8~13%、空格 0%）⇒ 三者只要有一路到门槛就算有子。
        let hasPiece = null;
        const ink = one.ink || null;
        if (ink && ink.area > 0) {
            const redDensity = (ink.red || 0) / ink.area;
            const blackDensity = (ink.black || 0) / ink.area;
            const darkDensity = (ink.dark || 0) / ink.area;
            hasPiece = redDensity >= 0.02 || blackDensity >= 0.02 || darkDensity >= 0.04;
        }
        const side = one.side === "RED" || one.side === "BLACK" ? one.side : null;
        let char = null;
        if (one.ok && one.cls && side) {
            const upper = String(one.cls).toUpperCase();
            char = side === "RED" ? upper : upper.toLowerCase();
        }
        return {
            hasPiece: hasPiece,
            side: side,
            char: char,
            cls: one.cls || null,
            noText: one.noText === true,
            ink: ink
        };
    }

    function evidenceCharFor(index) {
        const evidence = cellEvidence(index);
        return evidence ? evidence.char : null;
    }

    function ocrCheckPatch(patch) {
        const at = driverState.nowMono();
        if (patch.ocrAt && at - patch.ocrAt < driverState.cfg.ocrCooldownMs) {
            return { state: "unknown", reason: "限频" };
        }
        patch.ocrAt = at;
        let res = null;
        try {
            res = driverState.opts.ocrCell({ index: patch.index, char: patch.char });
        } catch (error) {
            res = null;
        }
        driverState.stats.ocrChecks += 1;
        if (!res) {
            return { state: "unknown", reason: "OCR 异常" };
        }
        if (res.ok && (res.cls || res.char)) {
            // 读到了字 ⇒ 这一格被证实了，把「连着读空」的计数清掉
            patch.ocrEmptyAt = 0;
            patch.ocrEmptySeen = 0;
            // ⚠️ 只比**类别**（方案 §13：字形不带红黑）；颜色以已知事实为准。
            const cls = String(res.cls || res.char);
            const sameGlyph = cls.toLowerCase() === String(patch.char).toLowerCase();
            const sameColor = (cls === cls.toUpperCase()) === (patch.char === patch.char.toUpperCase());
            return sameGlyph || sameColor
                ? { state: "match", cls: cls, glyph: res.glyph }
                : { state: "other", cls: cls, glyph: res.glyph };
        }
        // ⚠️ 只有「OCR 真的跑了但没读到字」才算候选空格；OCR 不可用/取帧失败要区分开（不能当作证据）
        if (res.noText === true) {
            // ★ 「读不到字」也要跨帧佐证：棋子可能**还在飞行中**（画面上隐形），
            //   拿那一帧判空会把真实事实丢掉（真机截图实锤：那枚子确实落在那一格）。
            const prev = patch.ocrEmptyAt || 0;
            patch.ocrEmptyAt = at;
            patch.ocrEmptySeen = (patch.ocrEmptySeen || 0) + 1;
            if (prev > 0 && at - prev >= driverState.cfg.ocrEmptyCorroborateMs) {
                return { state: "empty", seen: patch.ocrEmptySeen };
            }
            return {
                state: "unknown",
                reason: "判空待佐证（第 " + patch.ocrEmptySeen + " 次读到空，等下一次再确认）"
            };
        }
        return { state: "unknown", reason: res.reason };
    }

    /**
     * 把可靠基准里那一格按观测改正（置空）。
     * 为什么必须改：可靠基准是唯一权威，它带着一条无法证实的记录时，下一帧就会变成
     * 「基准里有子、观测里没子，没有任何合法着法解释得了」→ 永远 RECOVERING
     * （真机实测：未知格已归零，但停在「既没有合法单步也没有一步+应手能解释」）。
     */
    function resyncReliableAt(patch) {
        if (
            !patch ||
            !driverState.lastReliable ||
            !driverState.lastReliable.board ||
            driverState.lastReliable.board[patch.index] !== patch.char
        ) {
            return;
        }
        // ★ 修订前先登记旧基准：等对方时这次修订可能吞掉对方刚走的那一手（见 recordBaselineRevision）
        driverActions.recordBaselineRevision(
            "找色/OCR 作废 (" + (patch.index % 9) + "," + Math.floor(patch.index / 9) + ")" + patch.char
        );
        const fixed = driverState.lastReliable.board.slice();
        fixed[patch.index] = null;
        driverState.lastReliable = { board: fixed, side: driverState.lastReliable.side };
        driverActions.trackFix(fixed, driverState.lastReliable.side, { reason: "resync" });
        driverState.baselineCorrectedAt = driverState.nowMono();
        driverActions.note(
            "可靠基准已按观测改正那格（(" +
                (patch.index % 9) +
                "," +
                Math.floor(patch.index / 9) +
                ") 置空）：否则下一帧会「少一个子且解释不了」"
        );
    }

    /**
     * 用「当前可靠基准 + 我方这手」重建票据里的两个盘面：`baseFen` 与 `expectedAfterFen`。
     *
     * 为什么要重建**两个**（2026-09-19，K40）：验证器既用 `expectedAfterFen`（期望落子后局面），
     * 也在 UNEXPECTED 分支里跟 `baseFen` 对差。只重建后者时，旧 `baseFen` 里那张带假事实的
     * 基准仍然在作祟 —— 真机日志：`UNEXPECTED：无法解释的局面（与基准差 3 格：
     * (col 4,row 2) c→空；…）`，而那时的可靠基准已经没有那枚子了。
     *
     * 只在「基准刚被按观测改正过」之后的一段时间内重建 —— 那时票据里的旧盘面必然已过期
     * （它把被证伪的那枚子算进去了）。平时不重建，避免基准已经走到下一手时算错。
     *
     * @return { baseFen, expectedAfterFen }；没法重建（缺基准/着法不合法）返回 null
     */
    function rebuildTicketBoards(ticket) {
        if (!driverState.lastReliable || !driverState.lastReliable.board) {
            return null;
        }
        // ★ 2026-09-21 真机（v5 慢局）：这里以前自己 `applyMove(board, { iccs })` —— 而 applyMove
        //   不解析 iccs ⇒ 静默返回「没走这手」的盘面 ⇒ 期望 == base ⇒ 确认阶段把「真走成了」的
        //   观测判成「动画帧」无限等（四笔卡顿 20s/39s/120s/235s 的真因）。纯逻辑挪去验证器，
        //   那边显式解析 ICCS + isLegalMove 门（可离线自检）。
        if (typeof verifier.rebuildTicketBoards !== "function") {
            return null;
        }
        try {
            return verifier.rebuildTicketBoards(ticket, driverState.lastReliable.board);
        } catch (error) {
            return null;
        }
    }

    return {
        ocrCellEvidence: ocrCellEvidence,
        cellSideEvidence: cellSideEvidence,
        cellEvidence: cellEvidence,
        evidenceCharFor: evidenceCharFor,
        ocrCheckPatch: ocrCheckPatch,
        resyncReliableAt: resyncReliableAt,
        rebuildTicketBoards: rebuildTicketBoards
    };
}

module.exports = { create: create };
