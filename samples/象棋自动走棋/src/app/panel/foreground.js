// @engine quickjs
"use strict";

/** foreground：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    function describeCapabilities() {
        const caps = panelState.adapter.capabilities();
        const lines = [];
        lines.push(
            "截图：" +
                panelState.adapter.captureBackend() +
                " → 最近 " +
                (panelState.adapter.captureChannel() || "未用过") +
                "｜shell=" +
                (caps.captureShell ? caps.captureShellBackend : "不可用")
        );
        lines.push(
            "点击：无障碍=" +
                (caps.accessibilityReady ? "就绪" : "未就绪") +
                "，Shizuku=" +
                (caps.shizuku ? "可用" : "不可用")
        );
        return lines.join("｜");
    }

    /**
     * 引擎「带着法历史分析」通道是否可用（长将/长捉判罚的前提）。
     * 为什么单独探查：`pikafish-engine.analyze` 在宿主缺 `analyzePosition` 时会**静默退回**
     * `analyze(fen, …)` —— 日志上只写「历史 当前FEN」，看不出是「没有历史」还是「有历史但用不了」。
     */
    function engineHistorySupportText() {
        try {
            if (typeof $pikafish === "undefined" || !$pikafish) {
                return "宿主机没有 $pikafish（引擎不可用）";
            }
            if (typeof $pikafish.analyze !== "function") {
                return "宿主缺 $pikafish.analyze（引擎不可用）";
            }
            try {
                if (typeof $pikafish.available === "function" && $pikafish.available() !== true) {
                    return "⚠ $pikafish 不可用（原生库没加载？）";
                }
            } catch (error) {
                // 探不到就接着看 analyzePosition
            }
            if (typeof $pikafish.analyzePosition === "function") {
                return "支持 analyzePosition（能按完整着法历史算 → 长将/长捉判罚可算）";
            }
            return "⚠ 缺 analyzePosition：只能按当前 FEN 搜 → 重复局面/长将判罚用不上";
        } catch (error) {
            return "探测失败：" + error;
        }
    }

    function makeForegroundReader(read) {
        const cache = { value: null, at: 0, ttl: panelState.FG_TTL_MS, has: false };
        return function (force) {
            const now = Date.now();
            if (!force && cache.has && now - cache.at < cache.ttl) {
                return cache.value;
            }
            const started = Date.now();
            let value = null;
            try {
                value = read();
            } catch (error) {
                value = null;
            }
            const cost = Date.now() - started;
            if (cost > 800) {
                cache.ttl = Math.min(panelState.FG_TTL_MAX_MS, cache.ttl * 2);
                if (!panelState.fgSlowNoted) {
                    panelState.fgSlowNoted = true;
                    panelActions.logLine(
                        "前台读数耗时 " +
                            cost +
                            "ms（dumpsys via shell）：缓存期改成 " +
                            cache.ttl +
                            "ms，避免每几秒卡一次"
                    );
                }
            } else if (cost < 200) {
                cache.ttl = panelState.FG_TTL_MS;
            }
            cache.value = value;
            cache.at = Date.now();
            cache.has = true;
            return value;
        };
    }

    /**
     * 点击前的**前台守卫**（真机血泪，见 auto-driver 里的注释）：
     * 脚本宿主 RunIntentActivity 是透明窗口，`am start` 起脚本后它常常仍是「有窗口焦点的前台」，
     * 游戏只是画在它下面 —— 这时注入的点击会被我们自己吃掉，截图上却一切正常。
     *
     * 判据用 `dumpsys input` 的 FocusedWindows（**决定注入触摸给谁的就是它**）：
     * 真机踩过：按 topResumedActivity 判会得到 `com.android.launcher3` 这种误导值，
     * 守卫以为切成功、实际点击全落到别处（一手棋连试 4 次全 UNCHANGED）。
     *
     * 处理：焦点不是游戏 → ① 先按一次「最近任务」；② 还不行就用 `am start` 把游戏任务带到前台
     * （不会重启游戏，实测已重置的开局不会被恢复）；③ 仍不行就告诉驱动「别点」。
     *
     * @returns {ok, package, ownPackage, packageChanged}
     */
    function foregroundGuard() {
        // 出手前这一次必须**新鲜**（force=true）：被吃掉的点击比一次壳调用贵得多。
        // 其余调用方（心跳/等待对方）走的是同一个带缓存的读数（见 makeForegroundReader）。
        const readFocus = function () {
            return panelState.foregroundFocus(true);
        };
        let pkg = readFocus();
        panelState.lastForeground = pkg;
        if (pkg === null) {
            // 查不到（没有 Shizuku/root）就不拦：交给点击通道自己报错
            return { ok: true, package: null, ownPackage: false, packageChanged: false };
        }
        if (pkg === panelState.gamePackage) {
            return { ok: true, package: pkg, ownPackage: false, packageChanged: false };
        }
        const own = pkg === panelState.OWN_PACKAGE;
        let switched = false;
        // ① 直接把游戏的任务带到前台（`am start -n 包的已有 Activity`，不重启进程）。
        //    ⚠️ **不要用 `input keyevent 187`（最近任务）**：这台 ROM 上它会**打开「概览」界面**，
        //    下一次按键切到哪张任务卡不确定 —— 真机踩过：守卫「切成功了」但焦点落在桌面/概览，
        //    而驱动截到的画面是游戏的那张**缩略卡**（棋盘看得见、识别正常、点击全落空）。
        try {
            if (panelState.adapter.bringPackageToFront(panelState.gamePackage)) {
                sleep(1200);
                switched = true;
            }
        } catch (error) {
            switched = false;
        }
        let again = readFocus();
        // ② 还不行 → 按一次「最近任务」（个别 ROM 上这条才有效；只在①失败时才用）
        if (again !== panelState.gamePackage) {
            try {
                if (panelState.adapter.pressAppSwitch()) {
                    sleep(900);
                    switched = true;
                }
            } catch (error) {
                // 统一在下面判定
            }
            again = readFocus();
        }
        panelState.lastForeground = again;
        const ok = again === panelState.gamePackage;
        if (!ok) {
            panelActions.logLine(
                "前台守卫：切不到游戏（目标 " +
                    panelState.gamePackage +
                    "，当前焦点 " +
                    (again || "未知") +
                    "）—— 不会点击；请手动把游戏点到前台"
            );
        }
        return { ok: ok, package: again, ownPackage: own, packageChanged: switched && ok };
    }

    function wrapDriverDeps(deps) {
        const out = {};
        Object.keys(deps).forEach(function (name) {
            const value = deps[name];
            if (typeof value !== "function") {
                out[name] = value;
                return;
            }
            if (name === "stage") {
                // 崩溃前哨：纯日志，不参与「依赖 top」计时（否则每 tick 十几条会把它顶到第一）
                out[name] = value;
                return;
            }
            out[name] = function () {
                const started = Date.now();
                try {
                    return value.apply(null, arguments);
                } finally {
                    if (panelState.depAccum) {
                        const rec = panelState.depAccum[name] || (panelState.depAccum[name] = { calls: 0, ms: 0 });
                        rec.calls += 1;
                        rec.ms += Date.now() - started;
                    }
                }
            };
        });
        return out;
    }

    return {
        describeCapabilities: describeCapabilities,
        engineHistorySupportText: engineHistorySupportText,
        makeForegroundReader: makeForegroundReader,
        foregroundGuard: foregroundGuard,
        wrapDriverDeps: wrapDriverDeps
    };
}

module.exports = { create: create };
