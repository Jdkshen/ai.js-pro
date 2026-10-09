// @engine quickjs
"use strict";

/** parameters：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    /** 滑条选择的持久化（storages；取不到就退化成「本次会话生效」）。 */
    function paramStore() {
        try {
            if (typeof storages === "undefined" || typeof storages.create !== "function") {
                return null;
            }
            return storages.create("xiangqi-panel");
        } catch (error) {
            return null;
        }
    }

    /** 当前 Pikafish 线程数（面板没设过就是引擎默认 2）。 */
    function currentThreads() {
        const raw =
            panelState.pikafishOptions && panelState.pikafishOptions.threads != null
                ? Math.round(Number(panelState.pikafishOptions.threads))
                : 2;
        return isFinite(raw) && raw >= panelState.THREAD_MIN && raw <= panelState.THREAD_MAX ? raw : 2;
    }

    /** 把两个值（前缀、值、进度）拼成一行：无■□格子条 —— ± 控件的值段只有 187px，
     *  格子条会把「算棋 5.0s」这种主信息挤到看不清（真机 11sp 六格就截断过）。 */
    function paramText(prefix, valueText) {
        return prefix + " " + valueText;
    }

    function renderParamSliders() {
        if (panelState.thinkValueEl) {
            if (panelState.searchModeKey === "depth") {
                panelActions.setPanelText("thinkValueEl", panelState.thinkValueEl, paramText("目标", panelState.searchDepthKey + "层"));
            } else {
                let text = paramText(panelState.adaptiveSearchKey ? "上限" : "算棋", (panelState.movetimeMsKey / 1000).toFixed(1) + "s");
                if (panelState.thinkStepKey !== panelState.THINK_STEP_MS) {
                    text = (panelState.movetimeMsKey / 1000).toFixed(1) + "s ±" + (panelState.thinkStepKey / 1000) + "s"; // 步长不是 0.1 时把当前步长写出来
                }
                panelActions.setPanelText("thinkValueEl", panelState.thinkValueEl, text);
            }
        }
        if (panelState.win && panelState.win.searchModeBtn) {
            panelActions.setPanelText("searchMode", panelState.win.searchModeBtn, 
                panelState.searchModeKey === "depth"
                    ? "层 " + (panelState.depthTimeoutMsKey / 1000).toFixed(0) + "s"
                    : (panelState.adaptiveSearchKey ? "自动" : "限时")
            );
        }
        if (panelState.threadsValueEl) {
            panelActions.setPanelText("threadsValueEl", panelState.threadsValueEl, paramText("线程", String(currentThreads())));
        }
        if (panelState.openingValueEl) {
            panelActions.setPanelText("openingValueEl", panelState.openingValueEl, paramText("开局", String(panelState.bookOwnMovesKey)));
        }
        // 哈希是点按换档（不是滑条）：只显示当前值，档位表见 HASH_OPTIONS
        if (panelState.hashBtnEl) {
            panelActions.setPanelText("hashBtnEl", panelState.hashBtnEl, "哈希 " + panelState.hashMbKey + "M");
        }
        // 避和（Contempt）同样点按换档：档位表见 CONTEMPT_OPTIONS
        if (panelState.contemptBtnEl) {
            panelActions.setPanelText("contemptBtnEl", panelState.contemptBtnEl, "避和" + panelState.contemptKey);
        }
    }

    /**
     * 「−／＋」按钮：点一下走一步（算棋 ±0.1s、线程 ±1），**按住连发**（用户口径「按住自动加」）。
     *
     * 取值口径与旧滑条保持一致：值只改内存 + 立刻刷新显示，**松手才 `commit`**（写盘 + 应用引擎）。
     * 这样连发期间不会每 140ms 写一次盘；边界（min/max）顶住就停发，不空转。
     * 触摸面：ACTION_DOWN 即走一步（点一下的手感），UP/CANCEL 停发并落定（action 3 = 手势被抢）。
     */
    function bindStepControl(opts) {
        let holdActive = false;
        let holdTimer = null;
        let repeats = 0;
        let dirty = false;
        function spec() {
            return {
                min: typeof opts.min === "function" ? opts.min() : opts.min,
                max: typeof opts.max === "function" ? opts.max() : opts.max,
                step: typeof opts.step === "function" ? opts.step() : opts.step
            };
        }
        function stopHold() {
            holdActive = false;
            if (holdTimer != null) {
                try {
                    clearTimeout(holdTimer);
                } catch (error) {
                    /* 没有 clearTimeout 也不打紧 */
                }
                holdTimer = null;
            }
        }
        function stepOnce(dir) {
            const s = spec();
            const cur = opts.get();
            // 定点化：0.1 步进累加会攒浮点毛刺（5.0 + 0.1×7 ≠ 5.7），按步长取整
            let next = Math.round((cur + dir * s.step) / s.step) * s.step;
            next = Math.max(s.min, Math.min(s.max, next));
            if (next === cur) {
                return false; // 顶到边界：连发停住
            }
            opts.set(next);
            dirty = true;
            renderParamSliders();
            return true;
        }
        function startHold(dir) {
            stopHold();
            holdActive = true;
            repeats = 0;
            function tick() {
                if (!holdActive) {
                    return;
                }
                if (stepOnce(dir)) {
                    repeats += 1;
                    holdTimer = setTimeout(
                        tick,
                        repeats > 8 ? panelState.HOLD_REPEAT_FAST_MS : panelState.HOLD_REPEAT_MS
                    );
                } else {
                    stopHold();
                }
            }
            holdTimer = setTimeout(tick, panelState.HOLD_FIRST_DELAY_MS);
        }
        function settle() {
            stopHold();
            if (!dirty) {
                return;
            }
            dirty = false;
            const s = spec();
            const value = Math.max(s.min, Math.min(s.max, Math.round(opts.get() / s.step) * s.step));
            opts.commit(value);
        }
        function bindArrow(view, dir) {
            if (!view) {
                return;
            }
            view.on("touch", function (event) {
                let action = 1;
                try {
                    action = event.getAction();
                } catch (error) {
                    action = 1;
                }
                if (action === 0) {
                    stepOnce(dir);
                    startHold(dir);
                } else if (action === 1 || action === 3) {
                    settle();
                }
                return true;
            });
        }
        bindArrow(opts.minus, -1);
        bindArrow(opts.plus, 1);
    }

    /**
     * 值段点按 = 切换「± 按钮的步长」（2026-09-25 用户：「点一下加多少我想自己设」）。
     * 算棋时间：0.1 → 0.5 → 1.0s 循环（写盘）；固定层数模式恒 1 层（点一下只给提示）。
     */
    function bindStepSizeTap(view) {
        if (!view) {
            return;
        }
        view.on("touch", function (event) {
            let action = 1;
            try {
                action = event.getAction();
            } catch (error) {
                action = 1;
            }
            if (action !== 1) {
                return true; // 只认抬手（点一下）
            }
            if (panelState.searchModeKey === "depth") {
                panelActions.logLine("固定层数模式：± 每次 1 层（步长固定）；点模式键可换回限时");
                return true;
            }
            const index = panelState.THINK_STEP_OPTIONS.indexOf(panelState.thinkStepKey);
            panelState.thinkStepKey = panelState.THINK_STEP_OPTIONS[(index + 1) % panelState.THINK_STEP_OPTIONS.length];
            const store = paramStore();
            if (store) {
                try {
                    store.put("thinkStepMs", panelState.thinkStepKey);
                } catch (error) {
                    // 写不进就只生效本次
                }
            }
            renderParamSliders();
            panelActions.logLine(
                "± 按钮步长：" + (panelState.thinkStepKey / 1000).toFixed(1) + "s（点数值区可换 0.1/0.5/1.0）"
            );
            return true;
        });
    }

    return {
        paramStore: paramStore,
        currentThreads: currentThreads,
        paramText: paramText,
        renderParamSliders: renderParamSliders,
        bindStepControl: bindStepControl,
        bindStepSizeTap: bindStepSizeTap
    };
}

module.exports = { create: create };
