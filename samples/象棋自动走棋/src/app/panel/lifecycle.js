// @engine quickjs
"use strict";

/** lifecycle：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    /**
     * 下一轮什么时候跑：
     *   · 算棋中（worker 在自己的线程里转）→ 只轮询结果，120ms 一次；本轮不截图、不观察，
     *     所以密轮询很便宜，而且能在算完的第一时间出手（不用等到下一个 1400ms）。
     *   · 窗口视觉 worker 等帧/等轮次 → 120ms，仍只允许一个任务；保留跨帧佐证。
     *   · 其他 → 正常间隔（screencap 通道 0.6~1.4s/帧，密了只是白烧 CPU）。
     */
    function scheduleNextTick(firstDelay) {
        const thinking =
            panelState.driver && typeof panelState.driver.isThinking === "function" && panelState.driver.isThinking();
        const machine = panelState.driver && panelState.driver.machine;
        const state = machine && machine.state();
        const fastObserve = panelState.visionWorker && !panelState.visionWorkerFailed &&
            ["WAIT_STABLE", "WAIT_TURN", "RECOVERING"].indexOf(state) >= 0;
        const delay = firstDelay == null ? (thinking || panelState.observing || fastObserve ? 120 : 1400) : firstDelay;
        panelState.timer = setTimeout(function () {
            panelState.timer = null;
            if (!panelState.running) {
                return;
            }
            panelActions.tick();
            if (panelState.running) {
                scheduleNextTick();
            }
        }, delay);
    }

    function stopLoop() {
        if (panelState.timer) {
            clearTimeout(panelState.timer);
            panelState.timer = null;
        }
        panelActions.clearStartFeedback(); // 启动中途被停：别让按钮留在「启动中…」
        panelState.running = false;
        if (typeof panelActions.cancelVision === "function") panelActions.cancelVision();
        panelState.startError = "";
        if (typeof panelActions.clearOverlay === "function") panelActions.clearOverlay();
        if (!panelState.handingOff) {
            markPanelRunning(false); // 手动停/被停都要落「不在跑」（自动重载交接时保持 true）
        }
        if (panelState.app) {
            // A1：停止统一走会话（幂等；驱动与窗口兜底在会话里）
            try {
                panelState.app.session.stop("面板停止");
            } catch (error) {
                panelActions.logLine("停止驱动出错：" + error);
            }
        }
        panelActions.clearRecentFrame(); // 停止后不保留整屏原生帧
        if (panelState.ownMenuLease) panelState.ownMenuLease.release();
        panelState.loopLock.release(panelState.loopLock.OWNER_FLOATY); // 互锁：停了就把锁交出去
        // 点击租约兜底：异常路径（起点点了、落点没点）下面板可能还藏着，这里恢复回来
        if (typeof panelState.releaseTapLease === "function") {
            try {
                panelState.releaseTapLease();
            } catch (error) {
                panelActions.logLine("释放点击租约失败：" + error);
            }
        }
        panelActions.logLine("已停止：驱动已停、定时器已清、循环锁已释放（几何与校准结果保留）");
        if (typeof panelActions.panelLogFlush === "function") panelActions.panelLogFlush();
    }

    /**
     * 新会话（§14 / A1 批次2：从 sessionBtn 按钮体抽成命令实现，控制器与按钮共用）。
     * 停循环 → 驱动 newSession → 清服务层几何/缓存 → 面板备注。
     */
    function newSession() {
        stopLoop();
        if (panelState.driver && typeof panelState.driver.newSession === "function") {
            panelState.driver.newSession();
        }
        panelState.grid = null;
        if (panelState.boardService) {
            panelState.boardService.clear();
        } // 几何/缓存放服务层一份，新会话要一起清
        panelState.lastHistoryStatus = null; // 换局后历史重新开段，状态要重新报一次
        panelState.calibrationNote = "已换会话（几何清空）";
        panelActions.logLine("新会话：会话号 +1，票据与基准全部作废，几何已清空");
        panelActions.render();
    }

    function runSelfCheck() {
        try {
            const result = panelActions.load("tests/self-check.js");
            panelActions.logLine("离线自检：" + result.summary);
            for (let i = 0; i < Math.min(result.failures.length, 3); i++) {
                panelActions.logLine("  失败 " + (i + 1) + "：" + result.failures[i]);
            }
            return result;
        } catch (error) {
            panelActions.logLine("自检没能运行：" + error);
            return null;
        }
    }

    function runContractTests() {
        try {
            const result = panelActions.load("tests/test-cells-contract.js");
            panelActions.logLine("契约测试：" + result.summary);
            for (let i = 0; i < Math.min(result.failures.length, 3); i++) {
                panelActions.logLine(
                    "  失败 " + (i + 1) + "：" + result.failures[i].name + " → " + result.failures[i].message
                );
            }
            return result;
        } catch (error) {
            panelActions.logLine("契约测试没能运行：" + error);
            return null;
        }
    }

    /**
     * 把「面板在不在跑」落盘（storages，与滑动条设置同一个 store）。
     * 用途：native 崩溃（exit 钩子不会执行）后重开面板时自动继续；
     *      自动重载交接时保持 true（见 exit 钩子对 handingOff 的判断）。
     */
    function markPanelRunning(on) {
        try {
            const store = panelActions.paramStore();
            if (store) {
                store.put("running", on === true);
            }
        } catch (error) {
            // 落不了盘只是恢复不了自动继续，不影响当前运行
        }
    }

    /**
     * 自动重载（2026-09-26）：到点把面板「换一个新引擎」接着跑 ——
     * quickjs 偶发的内部崩溃（JS_DefineProperty 空指针，09-24/25/26 三次同点墓碑）JS 层拦不住，
     * 定期重启脚本能把「崩在长跑中」的概率摊薄。当前设置经 storages 保留、running 交给新实例续。
     *
     * 顺序（失败保护是核心）：
     *   ① 先拉起新实例 —— 起不来就什么都不动（老面板继续用，5 分钟后再试）；
     *   ② 成功后置 handingOff（exit 钩子别把 running 落成 false）→ stopLoop → 清所有定时器；
     *   ③ 脚本没有定时器会被引擎回收 → exit 钩子关窗口/画板，不留僵尸面板。
     */
    function performAutoReload() {
        const selfPath = (panelState.BASE_DIR === "." ? "" : panelState.BASE_DIR + "/") + panelState.PROBE_SELF_NAME;
        const singleton = panelActions.load("src/app/panel-singleton.js");
        try {
            if (typeof engines === "undefined" || !engines || typeof engines.execScriptFile !== "function") {
                throw new Error("宿主没有 engines.execScriptFile");
            }
            if (!singleton.beginHandoff(engines, files, panelState.PROBE_SELF_NAME)) {
                throw new Error("无法建立面板交接令牌");
            }
            const next = engines.execScriptFile(selfPath, { path: panelState.BASE_DIR });
            if (!next) throw new Error("新实例未创建");
        } catch (error) {
            singleton.endHandoff(files);
            panelState.autoReloadNextTryAt = Date.now() + 5 * 60 * 1000;
            panelActions.logLine("自动重载：新实例没起来（" + error + "）—— 老实例继续跑，5 分钟后再试");
            return;
        }
        panelState.handingOff = true; // 之后 exit 钩子不落 running=false（新实例要接着跑）
        panelActions.logLine(
            "自动重载：新实例已拉起（" +
                selfPath +
                "，已运行 " +
                Math.round(panelState.aliveSeconds / 60) +
                " 分钟）—— 本实例退出中"
        );
        try {
            stopLoop(); // 停循环 + 释放循环锁（新实例「开始」时要拿）
        } catch (error) {
            // 停不下来也要继续退出（定时器清掉就结束了）
        }
        panelState.autoReloadDone = true;
        try {
            clearInterval(panelState.aliveTimer);
            panelState.aliveTimer = null;
        } catch (error) {
            // 已经清过
        }
        try {
            if (typeof holdTimer !== "undefined" && holdTimer) {
                clearTimeout(holdTimer);
                holdTimer = null;
            }
        } catch (error) {
            // 没有长按定时器
        }
        panelActions.logLine("自动重载：定时器已清，关闭旧窗口并退出脚本");
        // A QuickJS script can remain alive on other runtime timers even after
        // our heartbeat is cleared. Close all of its windows now, then end the
        // engine explicitly; waiting for GC left a new pair every hour.
        for (const window of [panelState.win, panelState.compactWin, panelState.logWin]) {
            try { if (window) window.close(); } catch (error) { /* exit hook retries */ }
        }
        try { panelState.adapter.overlayHide(); } catch (error) { /* exit hook retries */ }
        if (typeof exit === "function") exit();
    }

    /**
     * 保活（真机踩过，必读）：
     *   悬浮窗的存活**完全靠脚本里挂着的定时器**。面板一开始没有定时器（要等你点「开始」才有），
     *   所以脚本会在启动后 ~19 秒被引擎回收：
     *     `QuickJsException: Script execution interrupted` → `Engine destroy`，
     *   而**窗口像素还留在屏幕上** —— 看着是活的、点任何按钮都没反应。
     *   修法：常驻一个 1 秒心跳，绝不 clearInterval；同时把「存活 Ns」写进状态栏，
     *   这样一眼能看出面板到底还活着没有。退出时把窗口关掉，不留「僵尸面板」。
     */
    function startKeepAlive() {
        if (panelState.aliveTimer) {
            return;
        }
        panelState.aliveTimer = setInterval(function () {
            const now = Date.now();
            if (panelState.panelLogBuf && panelState.panelLogBuf.length &&
                now-panelState.panelLogFlushAt>=2000 && typeof panelActions.panelLogFlush === "function")
                panelActions.panelLogFlush();
            if (panelState.visionWorker) panelState.visionWorker.keepAlive();
            if (panelState.singleton && !panelState.singleton.touch(engines, files, now)) {
                panelActions.logLine("面板占用已被另一实例接管，本实例退出");
                if (typeof exit === "function") exit();
                return;
            }
            // 互锁总线：发布面板状态 + 收页面命令（双向停止/配置同步）
            panelActions.pumpBus();
            // 心跳每 1s 一次 → **最长心跳间隔**就是「脚本线程被占多久」的滞后指标：
            // 空载 ≈1000ms；本线程里跑采集/识别（或退回同步算棋）时会被拉长。
            // ⚠️ 口径修正（小米8 探针实测）：不能再减 1000ms —— 定时器是「上一次触发 + 1000ms」重排的，
            //    减 1000 会把一次长阻塞少算一个时钟周期（实测 1279ms 的同步算棋只报出 290ms）。
            const gap = panelState.lastBeatAt ? now - panelState.lastBeatAt : 0;
            panelState.lastBeatAt = now;
            if (gap > 1200) {
                panelState.lagCount += 1;
                panelState.lagMax = Math.max(panelState.lagMax, gap);
                if (panelState.lagCount <= 3) {
                    // 多出来的那部分就是「本站线程一次被占多久」：空载 1s 心跳的间隔本该 ~1000ms，
                    // 观察一帧 ~500ms 时就是 ~1500ms（真机实测值）。
                    console.log(
                        "[floaty] 心跳间隔 " +
                            gap +
                            "ms（≈本站线程被占 " +
                            (gap - 1000) +
                            "ms：采集/识别，或退回的同步算棋）"
                    );
                }
            }
            panelState.aliveSeconds += 1;
            // 每 5 秒刷一次「触摸焦点在谁身上」：状态行要如实反映，不能让用户看着 `前台 游戏✓` 干等
            // （守卫只在出招前才查，中间被切走的话显示就是旧的）。
            // ⚠️ 2026-09-25：**只在会自动点棋板的模式里查**，而且走 `foregroundFocus()` 的缓存 ——
            //    它是 Shizuku 上的 `dumpsys input`（0.5~2s），空载时没有任何调用方需要它（以前
            //    1s 心跳每 5s 就卡一次，用户报「打开面板按钮要等几秒才活」）；只识别模式不看前台。
            //    auto/advice 下 `keepGameInFront()` 本来每 4s 就有一次读数，这里基本命中缓存。
            if (
                panelState.running &&
                (panelState.modeKey === "auto" || panelState.modeKey === "advice") &&
                panelState.aliveSeconds % 5 === 0
            ) {
                panelState.lastForeground = panelState.foregroundFocus(false);
            }
            // 着法历史状态（只在变化时记一行）：规则胜的前提，中途降级也要看得见
            if (panelState.driver) {
                panelActions.watchHistoryStatus();
            }
            try {
                if (panelState.collapsed && panelState.compactWin) {
                    if (panelState.compactWin.isImGui === true) panelState.compactWin.updateState();
                    else panelState.compactWin.compactStatus.setText(panelActions.compactStateText());
                } else {
                    panelState.win.status.setText(panelActions.statusLine());
                }
            } catch (error) {
                // 渲染失败不影响保活
            }
            if (panelState.aliveSeconds % 30 === 0) {
                panelActions.logLine(
                    "面板保活中（存活 " +
                        panelState.aliveSeconds +
                        "s，最长心跳间隔 " +
                        panelState.lagMax +
                        "ms，超 1.2s 的 " +
                        panelState.lagCount +
                        " 次）｜面板移动：自动贴板 " +
                        panelState.panelMoveCounts.snap +
                        "／藏显 " +
                        panelState.panelMoveCounts.hide +
                        " 次" +
                        (panelState.snapPanelEnabled ? "" : "（贴板已关）") +
                        panelActions.nativeFrameStatsText()
                );
            }
            // 崩溃兜底（2026-09-26）：到点自动重载（见 performAutoReload）。
            // 只在「安全时刻」动手：不在点击租约里、不在启动中、不在算棋中
            // （JS 单线程：心跳执行时 tick 一定没在跑，这里看到的一定是两个 tick 之间）。
            if (
                !panelState.autoReloadDone &&
                panelState.autoReloadMinKey > 0 &&
                Date.now() >= panelState.autoReloadNextTryAt &&
                panelState.aliveSeconds >= panelState.autoReloadMinKey * 60 &&
                !panelState.clickLeaseActive &&
                !panelState.starting && !panelState.observing &&
                !(
                    panelState.driver &&
                    typeof panelState.driver.isThinking === "function" &&
                    (panelState.driver.isEngineBusy ? panelState.driver.isEngineBusy() : panelState.driver.isThinking())
                )
            ) {
                performAutoReload();
                if (panelState.autoReloadDone) {
                    return; // 已交接：这条心跳不再做别的
                }
            }
        }, 1000);
        panelActions.logLine("保活心跳已启动（1s 一次；顺带度量脚本线程被占多久 —— 这个数就是「卡」的来源）");
        registerExitHook();
    }

    /** 退出时关掉窗口：否则脚本一结束就留下一个「点不动的僵尸面板」。 */
    function registerExitHook() {
        if (panelState.exitCleanupRegistered) return;
        try {
            if (typeof events !== "undefined" && events && typeof events.on === "function") {
                const cleanup=function () {
                    if (panelState.exitCleanupDone) return;
                    panelState.exitCleanupDone=true;
                    try {
                        if (panelState.app && typeof panelState.app.session.close === "function") panelState.app.session.close();
                        else if (panelState.driver && typeof panelState.driver.close === "function") panelState.driver.close();
                    } catch (error) { console.log("[floaty] 退出算棋队列失败："+error); }
                    try {
                        if (typeof panelActions.closePanelFiles === "function") panelActions.closePanelFiles();
                    } catch (error) { console.log("[floaty] 退出文件队列失败："+error); }
                    if (panelState.singleton) panelState.singleton.release(engines, files);
                    // 正常退出（手动关 / 被停）→ 落「不在跑」；自动重载交接时不落（新实例要接着跑）。
                    // native 崩溃走不到这里 —— 那正是恢复逻辑要接住的场景（保持上次的 true）。
                    if (!panelState.handingOff) {
                        try {
                            const store = panelActions.paramStore();
                            if (store) {
                                store.put("running", false);
                            }
                        } catch (error) {
                            // 落不了盘就算了
                        }
                    }
                    try {
                        if (typeof panelActions.closeVision === "function") panelActions.closeVision();
                        panelActions.clearRecentFrame();
                        panelActions.releaseOcr();
                        if (panelState.visionDetectorSession) {
                            panelState.visionDetectorSession.close();
                            panelState.visionDetectorSession = null;
                        }
                        panelState.visionPipeline = null;
                    } catch (error) {
                        // 模型资源释放失败也要继续关窗口
                    }
                    try {
                        panelState.adapter.overlayHide(); // 画板是独立悬浮窗，不收掉也会变僵尸窗口
                    } catch (error) {
                        // 收不掉就算了
                    }
                    for (const window of [panelState.win, panelState.compactWin, panelState.logWin]) {
                        try { if (window) window.close(); } catch (error) { /* continue closing the rest */ }
                    }
                };
                events.on("exit", cleanup);
                // QuickJS currently does not dispatch the local exit event for
                // exit(). Send cooperative shutdown while the bus is still alive.
                if (typeof globalThis.exit === "function") {
                    const originalExit=globalThis.exit;
                    globalThis.exit=function () {
                        try {cleanup();} catch (error) {console.log("[floaty] 退出清理失败："+error);}
                        return originalExit.apply(globalThis,arguments);
                    };
                }
                panelState.exitCleanupRegistered=true;
                panelActions.logLine("退出钩子已注册（脚本结束时会关掉面板，不留僵尸窗口）");
            } else {
                panelActions.logLine("运行时没有 events.on，退出时不会自动关窗（面板停住请手动 force-stop 应用）");
            }
        } catch (error) {
            panelActions.logLine("注册退出钩子失败：" + error);
        }
    }

    function readAutorun() {
        // §14：解析与校验只有一处（src/app/config.js）；面板只负责把结果应用到自己的变量上。
        // 读文件这一步从外面注进去，所以 config 不依赖宿主，随时能单测。
        const loaded = panelState.configModule.loadAutorun(function (path) {
            if (typeof files === "undefined" || !files || typeof files.read !== "function") {
                return null;
            }
            // ⚠️ 文件不存在是**正常情况**（多数人不用 autorun）：先 exists 再读。
            //    否则 files.read 抛 FileNotFoundException，会被 config 当成"致命配置错误"，
            //    日志里每次启动都堆两行红字、还写着"不自动启动"（2026-09-20 用户看到这个发懵）。
            try {
                if (typeof files.exists === "function" && !files.exists(path)) {
                    return "";
                }
            } catch (error) {
                // 判不了就照旧读（真读不到会走下面的错误分支，那才是需要看见的）
            }
            return files.read(path);
        }, panelState.AUTORUN_FILE);

        const issues = loaded.issues || [];
        for (let i = 0; i < issues.length; i++) {
            panelActions.logLine(
                "配置" +
                    (issues[i].level === "error" ? "错误" : "提示") +
                    "：" +
                    issues[i].key +
                    " — " +
                    issues[i].message
            );
        }
        panelState.autorunBlocking = panelState.configModule.hasBlockingIssue(issues);
        if (panelState.autorunBlocking) {
            panelActions.logLine("autorun 有致命配置错误 → 按约定**不自动启动**（拼错的通道名会把对照实验弄脏）");
        }
        if (loaded.empty || !loaded.raw) {
            panelActions.logLine("autorun：文件为空或不存在（路径 " + panelState.AUTORUN_FILE + "）");
            return null;
        }
        // 把「实际读的路径 + 原文」记下来：ADB 写的是哪个文件、脚本读的是哪个文件，
        // 不一致时（工程副本/示例副本/导入副本）会直接暴露出来，别再静默无效。
        panelActions.logLine(
            "autorun 文件 " + panelState.AUTORUN_FILE + " 原文 " + String(loaded.text || "").replace(/\s+/g, " ")
        );
        // 归一化后的值盖在原始对象上（校验/夹取已经做过，下面的块只负责应用）
        return Object.assign({}, loaded.raw, loaded.config);
    }

    return {
        scheduleNextTick: scheduleNextTick,
        stopLoop: stopLoop,
        newSession: newSession,
        runSelfCheck: runSelfCheck,
        runContractTests: runContractTests,
        markPanelRunning: markPanelRunning,
        performAutoReload: performAutoReload,
        startKeepAlive: startKeepAlive,
        registerExitHook: registerExitHook,
        readAutorun: readAutorun
    };
}

module.exports = { create: create };
