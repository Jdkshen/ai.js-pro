// @engine quickjs
"use strict";

/** 状态机动作分发：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const machineModule = require("../state-machine.js");

function create(driverState, driverActions) {
    function note(text) {
        driverState.history.push({ atMono: driverState.nowMono(), text: text });
        driverState.log("[driver] " + text);
    }

    /**
     * 空闲超时自动「翻轮次」（见 state-machine 的 opponentIdleDeclareMs 注释）：
     * 与用户点「轮到我」（assertTurn）同一条路 —— 把当前观测立成可靠基准（side = 我方）+ 声明。
     * 翻错有界：游戏拒绝非我方回合的点击 ⇒ 确认判 UNCHANGED ⇒ 尝试预算几次内自动暂停（可见）。
     */
    function declareTurnFromIdle(effect) {
        if (!driverState.lastBoard) {
            return null;
        }
        const bad = driverState.lastBoard.indexOf("?");
        if (bad >= 0) {
            note(
                "自动翻轮次跳过：当前局面还有未知格（第 " +
                    (Math.floor(bad / 9) + 1) +
                    " 行第 " +
                    ((bad % 9) + 1) +
                    " 列）"
            );
            return null;
        }
        driverState.lastReliable = { board: driverState.lastBoard.slice(), side: driverState.machine.mySide() };
        driverActions.trackAdopt(driverState.lastBoard, driverState.machine.mySide(), {
            reason: "declare-idle",
            source: "manual-confirmed"
        });
        driverState.frameConsensus.clear();
        driverState.phantomStreak = {};
        driverState.needFreshBaseline = false;
        note((effect.reason || "等对方空闲超时") + "：已按当前局面重立基准并翻到我方");
        return driverState.machine.dispatch({
            type: machineModule.EVENT.TURN_DECLARED,
            reason: effect.reason || "空闲超时自动翻轮次"
        });
    }

    // ---- 动作清单的执行 ----

    function handleEffect(effect) {
        // 崩溃前哨：动作类型先报出去（COMPUTE / EXECUTE_TICKET / VERIFY … 崩在哪段一眼可见）
        driverState.stage("effect:" + (effect && effect.type ? effect.type : "?"));
        switch (effect.type) {
            case "CALIBRATE":
                return calibrate();
            case "COMPUTE":
                return driverActions.compute(effect);
            case "SUGGEST":
                return onSuggest(effect);
            case "EXECUTE_TICKET":
                return driverActions.executeTicket(effect);
            case "VERIFY":
                return driverActions.doVerify(effect);
            case "RECOVER":
                return driverActions.recover();
            case "DECLARE_TURN":
                return declareTurnFromIdle(effect);
            case "INIT":
            case "CANCEL_TICKET":
                note(effect.type + "：" + (effect.reason || ""));
                return null;
            case "CANCEL_JOB":
                driverActions.cancelPrethink();
                // 局面变了/暂停：worker 里的旧任务立刻停掉，别白烧 CPU
                driverState.pendingPredict = null;
                if (driverState.workerSearch.isRunning()) {
                    driverState.workerSearch.cancel();
                    driverState.pendingCompute = null;
                    note("算棋任务已取消（worker 已停）：" + (effect.reason || ""));
                } else {
                    note(effect.type + "：" + (effect.reason || ""));
                }
                return null;
            default:
                return null;
        }
    }

    /** 把一段动作清单按顺序执行完（动作可能又产生新动作，用队列而不是递归）。 */
    function pump(outcome) {
        const queue = [outcome];
        let guard = 0;
        while (queue.length > 0 && guard++ < 24) {
            const current = queue.shift();
            for (let i = 0; i < current.effects.length; i++) {
                let follow = null;
                try {
                    follow = handleEffect(current.effects[i]);
                } catch (error) {
                    driverState.stats.errors += 1;
                    driverState.stats.lastError = String(error && error.message ? error.message : error);
                    note(
                        "动作 " +
                            current.effects[i].type +
                            " 失败：" +
                            driverState.stats.lastError +
                            (error && error.stack
                                ? "｜堆栈：" + String(error.stack).split("\n").slice(1, 4).join(" ← ")
                                : "")
                    );
                    follow = driverState.machine.dispatch({
                        type: machineModule.EVENT.ERROR,
                        reason: driverState.stats.lastError
                    });
                }
                if (follow) {
                    queue.push(follow);
                }
            }
        }
        /**
         * ★★ 2026-09-24（K40 真机根因）：返回前把 effects 清空。
         *
         * `handleEffect` 的返回值会被**调用方的 pump 再次入队**；而嵌套动作（executeTicket、
         * doVerify、compute…）自己也在调 `pump` —— 那些 effects 在里层已经执行过一次。
         * 不清空 = 同一组动作被内外两层各跑一遍：真机实测「一手棋的确认完整跑 2~3 次」
         * （每次白烧一整轮全盘观测 ~0.6s；09-24 早上用「同票据复用结论」兑过一次，
         * 但那只盖住症状、且仅对 ROI 路径生效 —— 去掉 OCR 后立刻复发）。
         * 清空后：每个 outcome 的 effects 只被执行一次（里层执行、外层看到空）。
         */
        outcome.effects = [];
        return outcome;
    }

    /**
     * ② 只识别 / 着法建议：状态机算出着法后直接把建议报出来（**不点击**）。
     * 这条日志就是面板「算棋/建议」那一格的来源；也方便 logcat 里对账。
     */
    function onSuggest(effect) {
        const ticket = effect.ticket || {};
        driverState.stats.suggestions = (driverState.stats.suggestions || 0) + 1;
        note("建议着法 " + ticket.moveICCS + "（② 只给建议，不点击）：评分 " + ticket.score + "，深度 " + ticket.depth);
        return null;
    }

    function notifyMovePlan(ticket) {
        if (typeof driverState.opts.onMovePlan !== "function") return;
        try {
            driverState.opts.onMovePlan({ ticket:ticket, result:driverState.lastResult,
                versions:driverState.machine.versions(), state:driverState.machine.state() });
        } catch (error) { note("走法绘制更新失败：" + error); }
    }

    function calibrate() {
        try {
            const located = driverState.opts.locate(driverActions.locateOptions());
            if (!located || !located.ok) {
                note("定位失败：" + (located ? located.reason : "没有结果"));
                return pump(
                    driverState.machine.dispatch({
                        type: "GEOMETRY",
                        trusted: false,
                        reason: located ? located.reason : ""
                    })
                );
            }
            driverState.grid = located.grid;
            driverState.lastReliable = null;
            driverActions.clearBaselineBorrow();
            driverActions.trackClear("calibrate");
            driverState.lastRelocateMono = driverState.nowMono(); // 定位过就记时间（recover 的限频用它）
            note(
                "棋盘已定位：起点 (" +
                    Math.round(driverState.grid.originX) +
                    "," +
                    Math.round(driverState.grid.originY) +
                    ") 格距 (" +
                    Math.round(driverState.grid.stepX) +
                    "," +
                    Math.round(driverState.grid.stepY) +
                    ") 版本 v" +
                    driverState.grid.version
            );
            const trusted = driverState.opts.isTrustworthy ? driverState.opts.isTrustworthy(driverState.grid) : true;
            return pump(
                driverState.machine.dispatch({
                    type: "GEOMETRY",
                    geometryVersion: driverState.grid.version,
                    trusted: trusted,
                    // 不可信时必须带原因：真机曾出现空原因，日志只剩「几何不可信：」（无从下手）
                    reason: trusted ? "" : "几何质量不足：" + JSON.stringify(driverState.grid.quality || null)
                })
            );
        } catch (error) {
            driverState.stats.errors += 1;
            driverState.stats.lastError = String(error && error.message ? error.message : error);
            note("定位异常：" + driverState.stats.lastError);
            return pump(
                driverState.machine.dispatch({ type: "GEOMETRY", trusted: false, reason: driverState.stats.lastError })
            );
        }
    }

    return {
        note: note,
        declareTurnFromIdle: declareTurnFromIdle,
        handleEffect: handleEffect,
        pump: pump,
        onSuggest: onSuggest,
        notifyMovePlan: notifyMovePlan,
        calibrate: calibrate
    };
}

module.exports = { create: create };
