// @engine quickjs
"use strict";

/** 识别融合与已知事实补齐：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const transition = require("../../chess/transition.js");
const legal = require("../../chess/legal-moves.js");
const safety = require("../observation-safety.js");

function create(driverState, driverActions) {
    /** 用已知事实补丁表把盘面上的 '?' 补掉（只补登记过、且当前确实未知的格子）。 */
    function applyKnownPatchesTo(chars) {
        return driverState.knownFacts.applyTo(chars);
    }

    function countUnknownChars(chars) {
        if (!chars) {
            return 0;
        }
        let count = 0;
        for (let i = 0; i < chars.length; i++) {
            if (chars[i] === "?") {
                count += 1;
            }
        }
        return count;
    }

    /**
     * 各兵种的**物理上限**（每方一个；象棋无升变、棋子不会凭空多出来）
     * —— 用来否决「兜出不可能盘面」的填充（见顽固未知兜底处的硬校验）。
     */
    function glyphPieceCap(ch) {
        switch (String(ch).toUpperCase()) {
            case "P":
                return 5;
            case "R":
            case "N":
            case "C":
            case "B":
            case "A":
                return 2;
            case "K":
                return 1;
            default:
                return 99;
        }
    }

    function countGlyphChars(chars, ch) {
        if (!chars) {
            return 0;
        }
        let count = 0;
        for (let i = 0; i < chars.length; i++) {
            if (chars[i] === ch) {
                count += 1;
            }
        }
        return count;
    }

    // ---- 观察 ----

    /** 定位参数：棋盘方向 + 可选的范围比例。 */
    function locateOptions() {
        const options = { orientation: driverState.cfg.orientation };
        if (driverState.cfg.regionRatio) {
            options.regionRatio = driverState.cfg.regionRatio;
        }
        return options;
    }

    /**
     * 观察层的「已知事实补齐」：用**唯一解释**把少数未知格补出来。
     *
     * 为什么需要：游戏给**刚落的子**画高亮环，那一格会被模型认错（实测带环的炮读成马），
     * 棋规层再降级成 '?'。而状态机的轮次推断要求 `unknownCells === 0` —— 于是对方刚应完一手，
     * 驱动就“看不见轮次” → 超时进 RECOVERING；`recover()` 又把基准清掉，而局面已经不是
     * 标准开局 → **再也推不出轮次，永久卡死**（K40 实测：走完第一手后就卡在这）。
     *
     * 两种**可验证**的解释（都不是猜，全都要精确对上其余所有已知格）：
     *   F1 与上一张可靠盘面逐格一致（对方那手的环还在）→ 直接按基准补；
     *   F2 恰好等于「上一张可靠盘面 + 某一个合法着法」→ 且只有**唯一一种**解释。
     * 解释不唯一或对不上就返回 null，保持未知（宁可等着）。
     */
    function fillKnownFacts(chars, baseline) {
        const reliable=baseline || driverState.lastReliable;
        if (!reliable || !reliable.board) {
            return null;
        }
        const base = reliable.board;
        // ⚠️ 基准自己必须是**没有未知格**的（它的名字就是「已知事实」）。
        // 真机踩到：assertTurn 把一个仍然带 '?' 的局面当成了基准 → F1 把 '?' 原样抄进结果、
        // 还报「与上一张可靠盘面一致」并把 unknownCells 当成 0 → 下游 buildFen 抛
        // 「存在未知格，不能生成 FEN」→ 每轮都被当异常跳过，面板上只看到「轮次未知」。
        if (base.indexOf("?") >= 0) {
            return null;
        }
        const unknown = [];
        for (let i = 0; i < 90; i++) {
            if (chars[i] === "?") {
                unknown.push(i);
            }
        }
        if (unknown.length === 0) {
            return null;
        }
        const norm = function (ch) {
            return ch == null ? null : ch;
        };
        const matches = function (board) {
            for (let i = 0; i < 90; i++) {
                if (chars[i] === "?") {
                    continue;
                }
                if (norm(chars[i]) !== norm(board[i])) {
                    return false;
                }
            }
            return true;
        };
        // F1：除了未知格，其余格子与上一张可靠盘面完全一致
        // ★ 我方这手还在飞（已发出点击、还没确认）时，基准要**先走一手**再比：
        //   起点补空、落点补「走过去的那个子」。否则 F1 会照落子前的基准把**起点**
        //   补回一枚棋子 —— 真的幻影子（2026-09-20 K40：b2c2 之后基准变成 …1CC4C1…）。
        let f1Base = base;
        if (
            driverState.activeOwnMove &&
            driverState.activeOwnMove.fromIndex >= 0 &&
            driverState.activeOwnMove.destIndex >= 0
        ) {
            const moving = base[driverState.activeOwnMove.fromIndex];
            if (moving) {
                f1Base = base.slice();
                f1Base[driverState.activeOwnMove.destIndex] = moving;
                f1Base[driverState.activeOwnMove.fromIndex] = null;
            }
        }
        if (matches(f1Base)) {
            const board = chars.slice();
            for (let k = 0; k < unknown.length; k++) {
                board[unknown[k]] = f1Base[unknown[k]];
            }
            if (board.indexOf("?") >= 0) {
                return null; // 补不出确定的局面就别假装成功
            }
            return { board: board, how: "与上一张可靠盘面一致（对方那手的环还在）" };
        }
        // F2：能完整解释的合法着法（差异全落在未知格上）；多于一个时可用**预判裁决**点名
        const fits = [];
        const sides = [reliable.side, reliable.side === "w" ? "b" : "w"];
        for (let s = 0; s < sides.length && fits.length < 8; s++) {
            const moves = legal.allLegalMoves(base, sides[s]);
            for (let m = 0; m < moves.length && fits.length < 8; m++) {
                let after = null;
                try {
                    after = legal.applyMove(base, moves[m]).board;
                } catch (error) {
                    continue;
                }
                // 未知格只允许落在「这一步改动过的格子」上
                const diff = legal.diffBoards(base, after);
                const changed = {};
                for (let d = 0; d < diff.length; d++) {
                    changed[diff[d].row * 9 + diff[d].col] = true;
                }
                let allowed = true;
                for (let k = 0; k < unknown.length; k++) {
                    if (!changed[unknown[k]]) {
                        allowed = false;
                        break;
                    }
                }
                if (!allowed || !matches(after)) {
                    continue;
                }
                fits.push({ board: after, iccs: moves[m].iccs });
            }
        }
        let chosen = null;
        let viaPrediction = false;
        if (fits.length === 1) {
            chosen = fits[0];
        } else if (fits.length > 1) {
            /**
             * ★ 预判裁决（2026-09-25 晚，用户要求「预判对面多走几个子的步，来添加识别判断」）：
             *   多个合法着法都能解释这张观测（差异全落在未知格上）时，用引擎预判的**对面着法**
             *   点名 —— 唯一命中才认；点不出来就照旧放弃（宁可等，也不拿猜的盘面去算棋）。
             *   裁决出来的盘面同样要过「连续两帧同一解释」那道门（fillKnownFacts 的调用方）。
             */
            const prefered = transition.pickByPreference(
                fits,
                function (fit) {
                    return fit.iccs;
                },
                driverActions.predictionIccsFor(base, driverState.lastReliable.side)
            );
            if (prefered) {
                chosen = prefered;
                viaPrediction = true;
                driverState.stats.predictionTieBreaks += 1;
            }
        }
        if (!chosen || chosen.board.indexOf("?") >= 0) {
            return null;
        }
        return {
            board: chosen.board,
            how: viaPrediction
                ? "预判裁决：" + fits.length + " 个合法着法里按预判认定 " + (chosen.iccs || "?")
                : "唯一合法着法 " + (chosen.iccs || "?") + " 解释得通"
        };
    }

    /**
     * 多帧合并识别（默认 2 帧）：**两帧一致的格子才采信，不一致的降级成未知格**。
     *
     * 为什么必须这么干（K40 真机实测）：模型单帧会有**多处**孤立误读 —— 同一帧里同时出现
     * 「黑車(0,0) 读成空」「(0,1) 空位读成黑炮」「黑炮(7,2) 读成红炮」共 3~4 处。
     * 这种盘面任何推断都解释不了 → 轮次推不出来 → 8s 看门狗 → 永久 RECOVERING。
     * 而误读是**逐帧随机**的：第二帧往往是对的 → 取「两帧都同意」的格子，
     * 冲突格算未知格，交给已有的「唯一合法着法能解释就补齐」（fillKnownFacts）走 ——
     * 宁可拿一张“部分未知”的真盘面，也不要拿一张“看起来完整”的假盘面。
     *
     * 代价：每次观察多一帧（K40 shell 通道 ~1.2s/帧），tickMs 已留 2.4s 余量。
     */
    function recognizeMerged(request) {
        if (request && request.asyncObservation && typeof driverState.opts.recognizeAsync === "function") {
            const result = driverState.opts.recognizeAsync(request, {
                observeFrames:driverState.cfg.observeFrames, observeGapMs:driverState.cfg.observeGapMs,
                observeRetryOnUnknown:driverState.cfg.observeRetryOnUnknown,
                observeRetryGapMs:driverState.cfg.observeRetryGapMs
            });
            if (result != null) {
                if (!result.pending && result.mergeStats) {
                    Object.keys(result.mergeStats).forEach(function (key) {
                        driverState.stats[key] = (driverState.stats[key] || 0) + result.mergeStats[key];
                    });
                }
                return result;
            }
        }
        // ★ 逐次覆盖（observeFramesByState）：等待对方时 1 帧（省 ~0.4s/次观察）；其余走全局 observeFrames。
        const framesCfg =
            request && request.frames != null
                ? request.frames
                : driverState.cfg.observeFrames == null
                  ? 2
                  : driverState.cfg.observeFrames;
        const times = Math.max(1, Math.min(3, framesCfg));
        const frames = [];
        let first = null;
        for (let i = 0; i < times; i++) {
            if (i > 0 && driverState.cfg.observeGapMs > 0) {
                driverState.clock(driverState.cfg.observeGapMs);
            }
            const one = safety.protect(driverState.opts.recognize(request));
            // 几何变位使之前读数的点击坐标失效，后续帧不能退回已收集的旧盘面。
            if (one && (one.geometryChanged || one.geometryUncertain || one.royalUnsafe)) return one;
            if (i === 0) {
                first = one;
            }
            if (!one || !one.ok || !one.chars) {
                if (i === 0) {
                    return one; // 第一帧就失败：直接上报（下一轮再试）
                }
                break; // 后续帧失败就用已有结果
            }
            frames.push(one);
        }
        if (!frames.length) {
            return first;
        }
        if (request && request.independentRead && frames.length !== 2) {
            return { ok: false, reason: "独立核对需要两张有效新帧（本次只有 " + frames.length + " 张）" };
        }
        /** 严格合并：两帧一致的格子才采信，不一致的降级成未知格（见上面的长注释）。 */
        function combine(list) {
            const chars = list[0].chars.slice();
            const rawChars = (list[0].rawChars || list[0].chars).slice();
            let conflicts = 0;
            for (let f = 1; f < list.length; f++) {
                const raw = list[f].rawChars || list[f].chars;
                for (let k = 0; k < 90; k++) {
                    if (rawChars[k] !== raw[k]) rawChars[k] = "?";
                    if (chars[k] === list[f].chars[k]) {
                        continue;
                    }
                    chars[k] = "?";
                    conflicts += 1;
                }
            }
            let unknown = 0;
            for (let k = 0; k < 90; k++) {
                if (chars[k] === "?") {
                    unknown += 1;
                }
            }
            return { chars: chars, rawChars:rawChars, unknown: unknown, conflicts: conflicts, source: list[list.length - 1] };
        }
        let merged = combine(frames);
        driverState.stats.frameConflicts += merged.conflicts;
        driverState.stats.mergedFrames += 1;
        /**
         * ★ 2026-09-20：观察帧撞上「走子动画」时，同一格一帧读成子、一帧读成暗影 ⇒ 严格合并把
         * 它降级成未知格 ⇒ 「布局没生成」白等。做法：丢掉最早那帧、隔 `observeRetryGapMs` 补抓一帧重合并。
         */
        if (merged.unknown > 0 && frames.length >= 2 && driverState.cfg.observeRetryOnUnknown === true) {
            driverState.clock(driverState.cfg.observeRetryGapMs == null ? 420 : driverState.cfg.observeRetryGapMs);
            const again = safety.protect(driverState.opts.recognize(request));
            if (again && (again.geometryChanged || again.geometryUncertain || again.royalUnsafe)) return again;
            if (again && again.ok && again.chars) {
                const retry = combine([frames[frames.length - 1], again]);
                driverState.stats.observeRetries = (driverState.stats.observeRetries || 0) + 1;
                driverState.stats.frameConflicts += retry.conflicts;
                if (retry.unknown < merged.unknown) {
                    driverState.stats.observeRetryHits = (driverState.stats.observeRetryHits || 0) + 1;
                    driverActions.note("补一帧躲开走子动画：未知格 " + merged.unknown + " → " + retry.unknown);
                    merged = retry;
                }
            }
        }
        // 合并后的盘面挂到**最后一帧**上：框中心（点棋用）/几何 都要用最新的那一帧。
        const out = merged.source || first;
        if (times > 1) {
            out.chars = merged.chars;
            out.rawChars = merged.rawChars;
        }
        out.unknownCells = merged.unknown;
        return out;
    }

    return {
        applyKnownPatchesTo: applyKnownPatchesTo,
        countUnknownChars: countUnknownChars,
        glyphPieceCap: glyphPieceCap,
        countGlyphChars: countGlyphChars,
        locateOptions: locateOptions,
        fillKnownFacts: fillKnownFacts,
        recognizeMerged: recognizeMerged
    };
}

module.exports = { create: create };
