// @engine quickjs
/**
 * 会话状态（§14：保存本引擎状态快照、生成界面需要的数据、通知订阅者。
 * 明确不执行截图/搜索/点击等副作用，也不替代棋规状态机）。
 *
 * 以前这些值散落在面板的几十个模块级变量里（modeKey / running / grid / calibrationNote …），
 * 界面渲染直接读它们 → 谁改的、什么时候改的、改了要不要重画，全靠人记。
 */
"use strict";

function createSessionStore(initial) {
    let state = Object.assign({
        // 运行态
        running: false,
        mode: "watch",
        channel: "auto",
        pressMs: 350,
        hideSettleMs: 120,
        computeMode: "worker",
        drawMode: "off",
        // 环境态
        captureBackend: "shell",
        captureChannel: null,
        accessibilityReady: false,
        shizukuReady: false,
        focusPackage: null,
        focusIsGame: null,
        lockHeld: false,
        lockPath: null,
        // 棋盘态
        grid: null,
        calibrationNote: "",
        unknownCells: null,
        turn: null,
        board: null,
        fen: null,
        // 状态机
        machineState: "STOPPED",
        machineReason: "",
        // 统计（只读展示）
        stats: {},
        lastClickPlan: null,
        lastSuggest: null,
        budgetNote: ""
    }, initial || {});

    const subscribers = [];
    let revision = 0;

    function get(key) {
        return key == null ? state : state[key];
    }

    function patch(partial, why) {
        if (!partial) {
            return revision;
        }
        const keys = Object.keys(partial);
        let changed = false;
        for (let i = 0; i < keys.length; i++) {
            const key = keys[i];
            if (state[key] !== partial[key]) {
                state[key] = partial[key];
                changed = true;
            }
        }
        if (changed) {
            revision += 1;
            notify(why || "patch");
        }
        return revision;
    }

    function notify(why) {
        for (let i = 0; i < subscribers.length; i++) {
            try {
                subscribers[i](state, { revision: revision, why: why });
            } catch (error) {
                // 订阅者出错不能影响业务（界面重画失败也不该让循环停）
            }
        }
    }

    return {
        get: get,
        patch: patch,
        revision: function () { return revision; },
        subscribe: function (fn) {
            if (typeof fn === "function") {
                subscribers.push(fn);
            }
            return function () {
                const index = subscribers.indexOf(fn);
                if (index >= 0) {
                    subscribers.splice(index, 1);
                }
            };
        },
        /** 只读快照（给日志/审计用）。 */
        snapshot: function () {
            const out = {};
            const keys = Object.keys(state);
            for (let i = 0; i < keys.length; i++) {
                const value = state[keys[i]];
                out[keys[i]] = (value && typeof value === "object") ? JSON.parse(JSON.stringify(value)) : value;
            }
            return out;
        }
    };
}

module.exports = { createSessionStore: createSessionStore };
