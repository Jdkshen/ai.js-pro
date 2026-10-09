// @engine quickjs
"use strict";

/** startup：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    function main() {
        // 默认停在**棋盘上沿之上那条空木纹带**（见 snapPanelAboveBoard 的量测）：
        //   · 底端会挡住游戏自己的按钮排（菜单/显示分析/悔棋/提示）—— 用户投诉过；
        //   · 顶端（状态栏下方）会挡住对手头像/名字 —— 用户也投诉过；
        //   · 只有棋盘上方那条空带是真正不挡东西的（K40 ≈ 280..645）。
        // 定位到棋盘后会自动贴到棋盘上沿之上；用户拖过就不再抢。
        let startX = panelState.PANEL.marginX;
        let startY = 264;
        try {
            const size = panelState.adapter.screenSize();
            if (size && size.height > 0) {
                // 屏幕高 defaultTopRatio（0.10）是按 372 高调的基准位置；面板变高后等量上移
                // ⚠️ 2026-09-25：0.11 那档（264）+ 364 底边 628 正好压棋盘含子上沿（K40 ~635）→ 上移一档
                startY =
                    Math.round(size.height * panelState.PANEL.defaultTopRatio) - Math.max(0, panelState.PANEL_H - 372);
            }
            startY = panelActions.clampPanelY(startY, size);
        } catch (error) {
            // 量不到就用默认值
        }
        try {
            // 参数和日志先隐藏创建，避免启动时大面板先闪出来。
            panelState.win = floaty.window(panelState.XML, { x: startX, y: startY, visible: false });
            panelState.win.setContentVisibility(8);
            // visible:false 仅准备布局；根 View 隐藏后挂载，更多可直接切换内容。
            if (panelState.win.setVisibility(0) === false) throw new Error("参数窗口挂载失败");
        } catch (error) {
            // 从工程主界面的按钮拉起时最容易踩到：没有悬浮窗权限，窗口建不出来
            console.log("[floaty] 打不开悬浮窗（多半是没有悬浮窗权限）：" + error);
            try {
                toast("需要悬浮窗权限才能显示面板（设置 → 权限 → 悬浮窗）");
            } catch (inner) {
                // 没有 toast 就算了
            }
            return;
        }
        panelState.win.setTouchable(false);
        panelState.panelY = startY; // 记住我们自己摆的 y（snapPanelAboveBoard 靠它区分「用户拖过」）
        // ---- 日志窗（单独一个窗口，在屏幕下方；用户口径「悬浮窗面板在上面，日志在下面」）----
        // ⚠️ 它挡不到棋盘以外的东西：底边被 menuTop（85% 屏高）硬夹住，永不压底部菜单/提示。
        if (panelState.logWindowEnabled) {
            try {
                let logY = 0;
                try {
                    const band = panelActions.panelSafeBand(panelState.adapter.screenSize());
                    logY = band.bottom - panelState.LOG_H;
                } catch (error) {
                    logY = Math.round(2400 * 0.85) - 10 - panelState.LOG_H;
                }
                panelState.logWin = floaty.window(panelState.LOG_XML, { x: startX, y: logY, visible: false });
                panelState.logWin.setContentVisibility(8);
                if (panelState.logWin.setVisibility(0) === false) throw new Error("日志窗口挂载失败");
                // 日志窗落在棋盘与底部菜单之间的安全带，允许触摸后可手动上下查看完整长日志。
                panelState.logWin.setTouchable(true);
                panelState.logWin.logMeta.on("click", function () {
                    panelState.logFollowRequested = true;
                    panelActions.render();
                });
                panelState.logPanelY = logY;
                panelActions.logLine("日志窗已就绪（点更多查看，y=" + logY + "，底边不压底部菜单）");
            } catch (error) {
                panelState.logWin = null;
                console.log("[floaty] 日志窗建不出来（不影响面板）：" + error);
            }
        }
        // 默认直接显示 ImGui；旧宿主由 compact 模块降级为兼容小窗。
        panelActions.setPanelCollapsed(true);
        // ⚠️ 不要在这里 setSize(…)：真机上会把窗口框体弄坏（零高 / 熔成一条窄条），尺寸全部由 XML 的 px 指定。
        panelActions.logLine("面板已启动（" + panelActions.describeCapabilities() + "）");
        panelActions.logLine(
            "临时目录：" +
                panelState.FRAME_DIR +
                "｜autorun：" +
                panelState.AUTORUN_FILE +
                "（shell 可写，便于 adb 驱动）"
        );
        // 脚本指纹：确认手机上跑的到底是哪一份（工程副本/示例副本/导入副本很容易分家）
        panelActions.logLine(
            "脚本版本 PROBE_BUILD_ID=" + panelState.PROBE_BUILD_ID +
            " 源码 SHA256=" + panelState.PROBE_SOURCE_SHA
        );
        // 长将/长捉判罚要看引擎能不能吃「完整着法历史」：这里先探一次，别到输了才发现通道没开
        panelActions.logLine("引擎历史通道：" + panelActions.engineHistorySupportText());
        panelActions.logLine(
            "提示：面板默认停在棋盘上方空档（与棋盘上沿留 ≥" +
                panelState.PANEL.boardClearGap +
                "px，顶部不挡头像、底部不挡菜单/悔棋/提示），可拖标题栏改位置"
        );
        // 显示与运行都用真实存储值：启动日志早于心跳刷新，直接用默认值会显示偏差（2026-10-06）
        try { panelState.bookOwnMovesKey = require("../session-settings.js").read().bookOwnMoves; }
        catch (ignore) { }
        // 本地开局库（离线预审数据在手机上）：前 N 手命中即秒出，库外/用尽 → 手机引擎接手
        panelActions.logLine(
            panelState.bookOwnMovesKey > 0
                ? "开局库：前 " +
                      panelState.bookOwnMovesKey +
                      " 手优先出库（命中即秒出），之后引擎接手算棋（± 调「开局」）"
                : "开局库：关（± 把「开局」调成 1～" +
                      panelState.OPENING_MAX +
                      " 可启用；库文件 /sdcard/xiangqi-p4/opening-book-vetted.json）"
        );
        /**
         * ★ 2026-09-20 用户报「为什么点击不了」：面板是用 `am start` 拉起来的，脚本宿主
         *   RunIntentActivity 是个 **fillxfill 的透明窗口** ⇒ 它一旦在前台，用户对棋盘的每一次触摸
         *   都被它吃掉（游戏根本收不到）。“你以为悬浮窗在上面、其实游戏点不动”就是这么来的。
         *   循环跑起来后每 tick 的 keepGameInFront() 会自己纠回来，可**没开跑之前**没人管 ——
         *   所以面板一启动就延迟一秒把游戏带回前台一次（只做一次，不周期抢；用户自己切 App 不拦）。
         */
        setTimeout(function () {
            try {
                panelActions.keepGameInFrontOnce();
            } catch (error) {
                // 没有 Shizuku/root 就什么都不做
            }
        }, 1200);
        panelActions.startKeepAlive();

        // ---- 拖动 ----
        // 之前是自己在 JS 里拖：**每个 ACTION_MOVE 都调一次 win.setPosition()**，
        // 每次都要过 JS→Java 桥 + WindowManager.updateViewLayout，而且触摸事件排在脚本线程上
        // （自动循环/算棋一跑就更卡）——真机上手感就是「拖着走一顿一顿」。
        // K40/LineageOS 上整窗原生拖动会吞掉按钮事件，因此只绑定标题区并限频移动。
        // 只拖标题控件，按钮仍可点击；旧宿主保留限频 JS 兜底。
        let nativeTitleDrag = false;
        try {
            if (typeof panelState.win.setDragHandle === "function") {
                panelState.win.setDragHandle("title", function () {
                    panelState.panelUserMoved = true;
                });
                nativeTitleDrag = true;
            }
        } catch (error) {
            nativeTitleDrag = false;
        }
        panelActions.logLine(nativeTitleDrag ? "拖动：标题栏原生" : "拖动：标题栏兼容模式");
        if (!nativeTitleDrag) {
            let downX = 0;
            let downY = 0;
            let winX = startX;
            let winY = startY;
            let lastMoveAt = 0;
            panelState.win.title.on("touch", function (event) {
                const action = event.getAction();
                if (action === 0) {
                    downX = event.getRawX();
                    downY = event.getRawY();
                } else if (action === 2) {
                    const now = Date.now();
                    if (now - lastMoveAt < 40) return true;
                    lastMoveAt = now;
                    panelState.win.setPosition(winX + (event.getRawX() - downX), winY + (event.getRawY() - downY));
                } else if (action === 1) {
                    winX += event.getRawX() - downX;
                    winY += event.getRawY() - downY;
                    panelState.win.setPosition(winX, winY);
                    panelState.panelUserMoved = true;
                    panelActions.logLine(
                        "拖动到 (" +
                            Math.round(winX) +
                            "," +
                            Math.round(winY) +
                            ")（实测 " +
                            panelState.win.getX(true) +
                            "," +
                            panelState.win.getY(true) +
                            "）"
                    );
                }
                return true;
            });
        }
        // 触摸诊断：悬浮窗控件不在 uiautomator dump 里，只能靠日志判断「事件到底到没到」
        let touchLogs = 0;
        try {
            panelState.win.root.on("touch", function (event) {
                const action = event && typeof event.getAction === "function" ? event.getAction() : "?";
                if (action === 0 && touchLogs < 5) {
                    touchLogs += 1;
                    panelActions.logLine("触摸到达：down@(" + event.getRawX() + "," + event.getRawY() + ")");
                }
                return false;
            });
        } catch (error) {
            panelActions.logLine("根视图触摸监听没绑上：" + error);
        }

        panelState.win.modeBtn.on("click", function () {
            const index = panelState.MODES.indexOf(panelState.modeKey);
            panelState.modeKey = panelState.MODES[(index + 1) % panelState.MODES.length];
            if (panelState.running || panelState.starting) {
                panelActions.stopLoop();
                panelActions.startLoop();
            }
            require("../session-settings.js").write({mode:panelState.modeKey});
            panelState.bus.syncConfig("mode", panelState.modeKey); // 配置双向同步：页面跟着改
            panelActions.logLine("模式切换为 " + panelState.MODE_LABEL[panelState.modeKey]);
            panelActions.render();
        });
        panelState.win.startBtn.on("click", function () {
            // A1 批次2：启动/停止统一走控制器命令（实现见 ensureBoardService 里的 handlers）
            panelActions.runPanelCommand(panelState.running || panelState.starting ? "STOP" : "START");
            panelActions.render();
        });
        panelState.win.stopBtn.on("click", function () {
            panelActions.runPanelCommand("STOP");
            // 双向停止：页面（主界面）在跑也一起停掉（否则面板停了、页面还在抓屏/算棋）
            const other = panelState.bus.readOther();
            if (other && other.fresh && other.state && other.state.running) {
                panelState.bus.stopOther("面板点了停止");
                panelActions.logLine("已请页面（主界面）也停止");
            }
            panelActions.render();
        });
        panelState.win.locateBtn.on("click", function () {
            panelActions.runPanelCommand("RELOCATE");
            panelActions.render();
        });
        panelState.win.recognizeBtn.on("click", function () {
            panelActions.runPanelCommand("RECOGNIZE");
            panelActions.render();
        });
        panelActions.logLine("按钮绑定完成：模式/收起/开始/停止/定位/识别/轮到我/换我方/重开本局；诊断工具在主界面");

        // ---- 参数按钮（2026-09-25 用户：算棋时间/线程改 +/− 控制，点一下 ±0.1s／±1，按住连加）----
        panelState.thinkValueEl = panelState.win.thinkValue;
        panelState.threadsValueEl = panelState.win.threadsValue;
        panelState.openingValueEl = panelState.win.openingValue;
        panelState.hashBtnEl = panelState.win.hashBtn;
        panelState.contemptBtnEl = panelState.win.contemptBtn;
        panelState.win.searchModeBtn.on("click", function () {
            if (panelState.searchModeKey !== "depth") {
                panelState.searchModeKey = "depth";
                panelState.depthTimeoutMsKey = panelState.DEPTH_TIMEOUT_OPTIONS[0];
            } else {
                const index = panelState.DEPTH_TIMEOUT_OPTIONS.indexOf(panelState.depthTimeoutMsKey);
                if (index < 0 || index >= panelState.DEPTH_TIMEOUT_OPTIONS.length - 1) {
                    panelState.searchModeKey = "time";
                } else {
                    panelState.depthTimeoutMsKey = panelState.DEPTH_TIMEOUT_OPTIONS[index + 1];
                }
            }
            if (panelState.driver && typeof panelState.driver.setSearchDepth === "function") {
                panelState.driver.setSearchDepth(panelState.searchModeKey === "depth" ? panelState.searchDepthKey : 0);
            }
            if (panelState.driver && typeof panelState.driver.setDepthTimeout === "function") {
                panelState.driver.setDepthTimeout(panelState.depthTimeoutMsKey);
            }
            const store = panelActions.paramStore();
            if (store) {
                try {
                    store.put("searchMode", panelState.searchModeKey);
                    store.put("depthTimeoutMs", panelState.depthTimeoutMsKey);
                } catch (error) {
                    /* 本次仍生效 */
                }
            }
            panelActions.logLine(
                panelState.searchModeKey === "depth"
                    ? "固定层数：目标 " +
                          panelState.searchDepthKey +
                          " 层，最长等 " +
                          panelState.depthTimeoutMsKey / 1000 +
                          " 秒；点模式键可换 3/6/10 秒，超时用已完成层数"
                    : (panelState.adaptiveSearchKey ? "自动搜索，总上限：" : "限时搜索：") +
                        (panelState.movetimeMsKey / 1000).toFixed(1) + " 秒（策略菜单可关闭自动调节）"
            );
            panelActions.renderParamSliders();
        });
        panelActions.bindStepControl({
            minus: panelState.win.thinkMinus,
            plus: panelState.win.thinkPlus,
            min: function () {
                return panelState.searchModeKey === "depth" ? panelState.DEPTH_MIN : panelState.THINK_MIN_MS;
            },
            max: function () {
                return panelState.searchModeKey === "depth" ? panelState.DEPTH_MAX : panelState.THINK_MAX_MS;
            },
            step: function () {
                return panelState.searchModeKey === "depth" ? 1 : panelState.thinkStepKey;
            },
            get: function () {
                return panelState.searchModeKey === "depth" ? panelState.searchDepthKey : panelState.movetimeMsKey;
            },
            set: function (value) {
                if (panelState.searchModeKey === "depth") panelState.searchDepthKey = value;
                else panelState.movetimeMsKey = value;
            },
            commit: function (value) {
                if (panelState.searchModeKey === "depth") {
                    if (panelState.driver && typeof panelState.driver.setSearchDepth === "function")
                        panelState.driver.setSearchDepth(value);
                    const store = panelActions.paramStore();
                    if (store) {
                        try {
                            store.put("searchDepth", value);
                        } catch (error) {
                            /* 本次仍生效 */
                        }
                    }
                    panelActions.logLine(
                        "固定层数目标：" +
                            value +
                            " 层（下次算棋生效；最长等 " +
                            panelState.depthTimeoutMsKey / 1000 +
                            " 秒）"
                    );
                    return;
                }
                if (panelState.driver && typeof panelState.driver.setMovetime === "function") {
                    panelState.driver.setMovetime(value);
                }
                const store = panelActions.paramStore();
                if (store) {
                    try {
                        store.put("thinkMs", value);
                    } catch (error) {
                        // 写不进就只生效本次
                    }
                }
                panelActions.logLine("算棋时间：" + (value / 1000).toFixed(1) + "s（下次算棋生效）");
            }
        });
        panelActions.bindStepControl({
            minus: panelState.win.threadsMinus,
            plus: panelState.win.threadsPlus,
            min: panelState.THREAD_MIN,
            max: panelState.THREAD_MAX,
            step: 1,
            get: panelActions.currentThreads,
            set: function (value) {
                panelState.pikafishOptions = Object.assign({}, panelState.pikafishOptions || {}, { threads: value });
            },
            commit: function (value) {
                panelState.engineModule.configurePikafish({ threads: value });
                if (panelState.driver && typeof panelState.driver.setPikafish === "function") {
                    panelState.driver.setPikafish(panelState.pikafishOptions);
                }
                const store = panelActions.paramStore();
                if (store) {
                    try {
                        store.put("threads", value);
                    } catch (error) {
                        // 写不进就只生效本次
                    }
                }
                panelActions.logLine("Pikafish 线程：" + value + "（下次算棋前应用原生线程数）");
            }
        });
        // 开局库步数（2026-09-25 用户：「开局用开局库走到第 N 手，之后引擎接手算棋」）
        panelActions.bindStepControl({
            minus: panelState.win.openingMinus,
            plus: panelState.win.openingPlus,
            min: panelState.OPENING_MIN,
            max: panelState.OPENING_MAX,
            step: 1,
            get: function () {
                return panelState.bookOwnMovesKey;
            },
            set: function (value) {
                panelState.bookOwnMovesKey = value;
            },
            commit: function (value) {
                if (panelState.driver && typeof panelState.driver.setBookOwnMoves === "function") {
                    panelState.driver.setBookOwnMoves(value);
                }
                const store = panelActions.paramStore();
                if (store) {
                    try {
                        store.put("bookOwnMoves", value);
                    } catch (error) {
                        // 写不进就只生效本次
                    }
                }
                panelActions.logLine(
                    value > 0
                        ? "开局库：前 " +
                              value +
                              " 手找开局库要着法（命中即秒出，" +
                              (panelState.bookRandomKey ? "候选随机抽签" : "总用主着法") +
                              "），之后引擎接手算棋"
                        : "开局库：关（0 手）—— 全程由引擎实时算"
                );
            }
        });
        // 值段点按 = 切换 ± 步长（0.1 → 0.5 → 1.0s；固定层数模式恒为 1 层）
        panelActions.bindStepSizeTap(panelState.win.thinkValue);
        // 求胜模式（2026-09-25 用户：「老是和棋」）：不落后时避开会走回旧局面的着法（默认关）
        panelState.win.winSeekBtn.on("click", function () {
            panelState.winSeekKey = !panelState.winSeekKey;
            if (panelState.driver && typeof panelState.driver.setWinSeek === "function") {
                panelState.driver.setWinSeek(panelState.winSeekKey);
            }
            const store = panelActions.paramStore();
            if (store) {
                try {
                    store.put("winSeek", panelState.winSeekKey);
                } catch (error) {
                    // 写不进就只生效本次
                }
            }
            panelActions.logLine(
                panelState.winSeekKey
                    ? "求胜模式：开 —— 不落后（评分 ≥ 0）时：① 马上要判和的重复着法硬排除；② 等价兑子只在「引擎自己也有不亏太多的替代」时才改（绝不硬换、绝不强制亏棋）；⚠ 仍可能把和棋担成输棋"
                    : "求胜模式：关 —— 均势时按引擎原意走（接受重复/来回与兑子，和棋概率高）"
            );
            panelActions.render();
        });
        // 哈希（Hash）：点一下换一档（64→128→256→512→64），同样写盘 + 下次算棋生效
        panelState.win.hashBtn.on("click", function () {
            const index = panelState.HASH_OPTIONS.indexOf(panelState.hashMbKey);
            const next = panelState.HASH_OPTIONS[(index + 1) % panelState.HASH_OPTIONS.length];
            panelState.hashMbKey = next;
            panelState.pikafishOptions = Object.assign({}, panelState.pikafishOptions || {}, { hashMb: next });
            panelState.engineModule.configurePikafish({ hashMb: next });
            if (panelState.driver && typeof panelState.driver.setPikafish === "function") {
                panelState.driver.setPikafish(panelState.pikafishOptions);
            }
            const store = panelActions.paramStore();
            if (store) {
                try {
                    store.put("hashMb", next);
                } catch (error) {
                    // 写不进就只生效本次
                }
            }
            panelActions.logLine("哈希（Hash）：" + next + "MB（下次算棋前应用；内存紧就调小）");
            panelActions.renderParamSliders();
        });
        // 避和（Contempt）：点一下换一档（0→40→80→120→160→200→0），写盘 + 下次算棋生效
        // 用户口径（2026-09-26）：「感觉又是和棋路」→ 开放到悬浮窗自己调
        panelState.win.contemptBtn.on("click", function () {
            const index = panelState.CONTEMPT_OPTIONS.indexOf(panelState.contemptKey);
            const next = panelState.CONTEMPT_OPTIONS[(index + 1) % panelState.CONTEMPT_OPTIONS.length];
            panelState.contemptKey = next;
            panelState.pikafishOptions = Object.assign({}, panelState.pikafishOptions || {}, { contempt: next });
            panelState.engineModule.configurePikafish({ contempt: next });
            if (panelState.driver && typeof panelState.driver.setPikafish === "function") {
                panelState.driver.setPikafish(panelState.pikafishOptions);
            }
            const store = panelActions.paramStore();
            if (store) {
                try {
                    store.put("contempt", next);
                } catch (error) {
                    // 写不进就只生效本次
                }
            }
            panelActions.logLine(
                "避和（Contempt）：" + next + "（下次算棋前应用；0 = 接受和棋，越大越愿意冒险保留复杂局面）"
            );
            panelActions.renderParamSliders();
        });
        panelActions.refreshSharedSettings();
        panelActions.renderParamSliders();
        // 抓帧遮挡保持自动判定；几何微调保留诊断配置，默认关闭。
        // 「现在轮到我」：把当前观察到的局面当可靠基准（轮次未知时状态机会一直等）
        panelState.win.turnBtn.on("click", function () {
            // A1 批次2：命令式（带 side 参数；“没有驱动”由 handler 报错）
            panelActions.runPanelCommand("DECLARE_TURN", {
                side: panelState.driver ? panelState.driver.mySide() : "w"
            });
            panelActions.render();
        });
        panelState.win.sideBtn.on("click", function () {
            const next = panelState.mySideKey === "w" ? "b" : "w";
            panelActions.setPanelSide(next);
            panelState.bus.syncConfig("side", next); // 配置双向同步：页面跟着改
            panelActions.logLine("我方改为 " + (next === "w" ? "红方" : "黑方") + "（旧基准已作废，需要重新声明轮次）");
            panelActions.render();
        });
        panelState.win.strategyBtn.on("click", function () {
            require("../../ui/strategy-menu.js").show(panelActions.logLine, panelActions.refreshSharedSettings);
        });
        panelState.win.sessionBtn.on("click", function () {
            // A1 批次2：按钮只发命令（实现见 newSession()）
            panelActions.runPanelCommand("NEW_SESSION");
        });
        panelState.win.collapseBtn.on("click", function () {
            panelActions.setPanelCollapsed(!panelState.collapsed);
        });

        panelActions.render();
        panelState.readyAt = Date.now();
        panelActions.logLine("准备就绪：选择模式，再点「开始」；更多可设置红黑方、参数并查看日志。");
        panelActions.logLine("拖动标题可移动小窗；点「更多」打开完整面板，点「停止」结束运行。");
        panelActions.stageMark("panel:ready");
        panelActions.pumpBus();
        // 界面先就绪，自动配置/探针在下一轮事件循环执行。
        setTimeout(function () {
        const auto = panelActions.readAutorun();
        if (auto) {
            panelActions.logLine("检测到 autorun：" + JSON.stringify(auto));
            if (auto.mode) panelState.modeKey = String(auto.mode);
            if (auto.drawMode && panelState.DRAW_ORDER.indexOf(String(auto.drawMode)) >= 0)
                panelState.drawMode = String(auto.drawMode);
            // ★ 2026-09-25（用户：「为什么启动就会这样」）：启动那一下游戏多半不在前台（焦点在宿主/面板窗口上），
            //   而 autorun 的自动定位在这之后 ~0.1s 就抓帧了，可「把游戏带回前台」要等 1s（设计如此）——
            //   于是第一次定位必然连着失败两条（线定位 / pickGrid），白烧一帧 + 几秒。
            //   凡是要看棋盘的 autorun 动作，这里先抢一手把游戏带回前台
            //   （幂等：已经在游戏前台就什么都不做，连壳调用都省了）。
            if (auto.locate || auto.captureProbe || auto.selftest || auto.tapProbe) {
                panelActions.keepGameInFrontOnce();
            }
            if (auto.captureBackend) {
                // 抓帧探测/定位前就应用，不能让第一帧沿用上次保存的手动偏好。
                const key = panelState.adapter.setCaptureBackend(auto.captureBackend, { persist: false });
                panelState.capturePermissionAttempted = false;
                panelActions.logLine("截图通道偏好：" + key + "（实际通道会随抓帧自动更新）");
            }
            if (typeof auto.collapse === "boolean") {
                panelActions.setPanelCollapsed(auto.collapse);
                panelActions.logLine(auto.collapse
                    ? "面板使用小操作窗（autorun.collapse=true）"
                    : "面板已展开（autorun.collapse=false，显示完整参数和日志）");
            }
            if (auto.captureProbe) {
                // 抓帧探测：两条截图通道到底哪条能用（最基础的可用性检查）
                const grabbed = panelActions.grabFrame();
                let size = "?";
                if (grabbed) {
                    try {
                        const info = panelState.adapter.sizeOf(grabbed.image);
                        size = info ? info.width + "×" + info.height : "尺寸未知";
                    } catch (error) {
                        size = "尺寸读取失败";
                    }
                }
                panelActions.logLine(
                    "抓帧探测：" + (grabbed ? "成功，" + grabbed.channel + " " + size : "失败（两条通道都没有帧）")
                );
            }
            if (auto.tapProbe) {
                // 单手探针（§10）：只发一手的两次手势 + 像素证据，通道对照实验用
                const result = panelActions.runTapProbe(auto.tapProbe);
                if (result && auto.stopAfterProbe) {
                    panelActions.logLine("autorun：探针结束，按配置不启动自动循环");
                    return;
                }
            }
            if (auto.selftest) {
                panelActions.runSelfCheck();
            }
            if (auto.contract) {
                panelActions.runContractTests();
            }
            if (auto.locate) {
                panelActions.locateAndCalibrate();
            }
            if (auto.recognize) {
                panelActions.recognizeOnce();
            }
            if (auto.ocrBoard != null) {
                panelActions.logLine("autorun 的 ocrBoard 已废弃：运行中只用字形识别");
            }
            if (auto.drawMode) {
                // 初始绘制档：off / moves / grid / cells / full（审核绘制前应用）
                const key = String(auto.drawMode);
                if (panelState.DRAW_ORDER.indexOf(key) >= 0) {
                    panelState.drawMode = key;
                    panelActions.logLine("识别绘制档位：" + panelState.DRAW_LABEL[panelState.drawMode]);
                }
            }
            if (auto.auditDraw) {
                // 人审用：抓一帧→认一帧→把结果画在冻结画面上（+落盘 audit-frame.png/audit-overlay.json）。
                // 无人工验证时靠它拿「带绘制的一帧」。
                panelActions.auditDraw();
            }
            if (auto.mode) {
                panelState.modeKey = String(auto.mode); // 合法性已在 config 校验，这里只应用
                panelActions.logLine("模式设为 " + panelState.MODE_LABEL[panelState.modeKey]);
            }
            if (auto.autoAlign != null) {
                panelState.autoAlignOn = !!auto.autoAlign;
                panelActions.logLine("几何自动微调：" + (panelState.autoAlignOn ? "开" : "关"));
            }
            // ⚠️ 必须**独立于** autoAlign 判断：autorun 里通常不写 autoAlign，
            //    塞在上面的块里会让 visionPipeline 静默不生效（2026-09-20 真机踩过：
            //    日志里没有任何「视觉流水线」字样，驱动其实还在走旧路径）。
            if (auto.visionPipeline != null) {
                panelActions.logLine("autorun 的 visionPipeline 已废弃：识别链只有字形识别（无差分/流水线/旧路径）");
            }
            if (auto.glyph != null) {
                panelActions.logLine("autorun 的 glyph 开关已废弃：字形识别是唯一识别链（始终启用）");
            }
            if (auto.ocrGray != null || auto.ocrPrimary != null) {
                panelActions.logLine("autorun 的 ocrGray/ocrPrimary 已废弃：棋子只用字形识别");
            }
            // 占用扫描取样框（差分模式）：默认 0.9×格；autorun `"scanInner": true` 切 0.6×格
            // （排除游戏画的选中/落子红环，真机实测环半径≈48px）—— 2026-09-20 A/B 实测 0.6 **更差**，
            // 详见 scanRegionFor / scanRatio 的注释；留着开关但别当默认。
            if (auto.scanInner != null) {
                panelState.scanRatio = auto.scanInner === true ? 0.6 : 0.9;
                panelActions.logLine(
                    "占用扫描取样框：" +
                        (panelState.scanRatio === 0.6 ? "0.6×格（排除游戏画的环，实验档）" : "0.9×格（默认）")
                );
            }
            if (auto.gamePackage) {
                // 目标游戏包名（前台守卫要用它判断「焦点在不在游戏上」；默认腾讯天天象棋）
                panelState.gamePackage = String(auto.gamePackage).trim();
                panelActions.logLine("目标游戏包名：" + panelState.gamePackage);
            }
            if (auto.clickMethod) {
                // 非法值**不静默退回 auto**：原样透传，让 createTap 在 buildDriver 里直接报错停下来
                panelState.clickMethodKey = String(auto.clickMethod);
                const legal = panelState.labelsModule.CHANNELS.indexOf(panelState.clickMethodKey) >= 0;
                panelActions.logLine(
                    "点击通道偏好：" +
                        panelState.clickMethodKey +
                        (legal
                            ? ""
                            : "（⚠️ 非法值：只支持 " +
                              panelState.labelsModule.CHANNELS.join(" / ") +
                              "，启动时会直接报错）")
                );
            }
            if (auto.clickHideSettleMs != null) {
                panelState.clickHideSettleMs = Number(auto.clickHideSettleMs);
                panelActions.logLine("点击前藏窗等待：" + panelState.clickHideSettleMs + "ms");
            }
            if (auto.autoReloadMin != null) {
                // 自动重载（分钟，0 = 关）：quickjs 引擎偶发内部崩溃拦不住，定期换新引擎摊薄概率
                const min = Math.round(Number(auto.autoReloadMin));
                panelState.autoReloadMinKey = isFinite(min) && min > 0 ? Math.min(24 * 60, min) : 0;
                panelActions.logLine(
                    "自动重载：" +
                        (panelState.autoReloadMinKey > 0
                            ? "每 " + panelState.autoReloadMinKey + " 分钟重启一次脚本（当前设置经 storages 保留）"
                            : "关")
                );
            }
            if (auto.clickPressMs != null) {
                panelState.clickPressMs = Number(auto.clickPressMs);
                panelActions.logLine("点击按压时长：" + panelState.clickPressMs + "ms");
            }
            if (auto.movetimeMs != null) {
                // 算棋时间预算（2026-09-21 提速方案第 1 条：接入可配置的 movetimeMs）
                const mt = Math.round(Number(auto.movetimeMs));
                if (isFinite(mt) && mt >= 100 && mt <= 60000) {
                    panelState.movetimeMsKey = mt;
                    panelActions.logLine("算棋时间预算：" + panelState.movetimeMsKey + "ms（默认 5000）");
                } else {
                    panelActions.logLine(
                        "算棋时间预算非法（" + auto.movetimeMs + "），沿用 " + panelState.movetimeMsKey + "ms"
                    );
                }
            }
            if (auto.bookOwnMoves != null) {
                // 开局库步数：前 N 手用库、之后引擎接手（0 = 不用库）
                const bookSteps = Math.round(Number(auto.bookOwnMoves));
                if (isFinite(bookSteps) && bookSteps >= panelState.OPENING_MIN && bookSteps <= panelState.OPENING_MAX) {
                    panelState.bookOwnMovesKey = bookSteps;
                    panelActions.logLine(
                        "开局库：" + (bookSteps === 0 ? "关（全程引擎）" : "前 " + bookSteps + " 手用库，之后引擎接手")
                    );
                } else {
                    panelActions.logLine(
                        "开局库步数非法（" +
                            auto.bookOwnMoves +
                            "，允许 " +
                            panelState.OPENING_MIN +
                            "～" +
                            panelState.OPENING_MAX +
                            "）"
                    );
                }
            }
            if (auto.bookRandom != null) {
                // 开局库候选随机（用户：「每次都一样，搞随机的吧」）：false = 回退到「总用主着法」
                panelState.bookRandomKey = auto.bookRandom !== false;
                if (panelState.driver && typeof panelState.driver.setBookRandom === "function") {
                    panelState.driver.setBookRandom(panelState.bookRandomKey);
                }
                panelActions.logLine(
                    "开局候选：" +
                        (panelState.bookRandomKey
                            ? "随机（同一局面每次可能不同，分数差距 >30cp 的线不抽）"
                            : "确定性（总用主着法）")
                );
            }
            if (auto.replyGear != null) {
                // 对面应手预判档位（2026-10-06）：关/快/标准/深 —— 面板「策略战绩」菜单也可改
                const gear = String(auto.replyGear);
                const settingsModule = require("../session-settings.js");
                if (["off", "quick", "std", "deep"].indexOf(gear) >= 0) {
                    panelState.replyGearKey = gear;
                    panelActions.logLine("对面预判档位：" + settingsModule.replyLabel(gear) + "（策略菜单可改）");
                } else {
                    panelActions.logLine(
                        "对面预判档位非法（" + auto.replyGear + "），沿用 " + settingsModule.replyLabel(panelState.replyGearKey)
                    );
                }
            }
            if (auto.winSeek != null) {
                // 求胜模式：均势时避开会走回旧局面的着法（默认关）
                panelState.winSeekKey = auto.winSeek === true;
                panelActions.logLine("求胜模式：" + (panelState.winSeekKey ? "开" : "关"));
            }
            if (auto.thinkStepMs != null) {
                // ± 按钮步长（点数值区也能换）：100 / 500 / 1000ms
                const step = Math.round(Number(auto.thinkStepMs));
                if (panelState.THINK_STEP_OPTIONS.indexOf(step) >= 0) {
                    panelState.thinkStepKey = step;
                    panelActions.logLine("± 按钮步长：" + (step / 1000).toFixed(1) + "s");
                } else {
                    panelActions.logLine("± 按钮步长非法（" + auto.thinkStepMs + "，允许 100/500/1000）");
                }
            }
            if (auto.depthTimeoutMs != null) {
                const wait = Math.round(Number(auto.depthTimeoutMs));
                if (isFinite(wait) && wait >= 1000 && wait <= 30000) {
                    panelState.depthTimeoutMsKey = wait;
                } else {
                    panelActions.logLine("固定层数最长等待非法：" + auto.depthTimeoutMs + "（允许 1000～30000ms）");
                }
            }
            if (auto.searchDepth != null) {
                const depth = Math.round(Number(auto.searchDepth));
                if (isFinite(depth) && depth >= panelState.DEPTH_MIN && depth <= panelState.DEPTH_MAX) {
                    panelState.searchDepthKey = depth;
                    panelState.searchModeKey = "depth";
                    panelActions.logLine(
                        "固定层数目标：" + depth + " 层（最长等 " + panelState.depthTimeoutMsKey / 1000 + " 秒）"
                    );
                } else if (depth === 0) {
                    panelState.searchModeKey = "time";
                } else {
                    panelActions.logLine("固定层数非法：" + auto.searchDepth + "（允许 6～24）");
                }
            }
            if (auto.clickSettleMs != null) {
                // 两击间隔（2026-09-21 提速方案第 1 条：接入可配置的 clickSettleMs）
                const cs = Math.round(Number(auto.clickSettleMs));
                if (isFinite(cs) && cs >= 0 && cs <= 3000) {
                    panelState.clickSettleMsKey = cs;
                    panelActions.logLine("两击间隔：" + panelState.clickSettleMsKey + "ms");
                } else {
                    panelActions.logLine("两击间隔非法（" + auto.clickSettleMs + "），沿用默认公式");
                }
            }
            if (auto.computeMode) {
                // "worker"（默认，独立引擎线程）/ "inline"（本线程同步，对照老路径）
                panelState.computeModeKey = String(auto.computeMode) === "inline" ? "inline" : "worker";
                panelActions.logLine("算棋模式：" + panelState.computeModeKey);
            }
            if (auto.visionMode) {
                panelState.visionModeKey = String(auto.visionMode);
                panelActions.logLine("视觉模式：" + panelState.visionModeKey);
            }
            if (auto.pikafish) {
                // Pikafish 参数覆盖（worker 与 inline 都会带过去）：
                //   "pikafish": {"evalFile":"/sdcard/xiangqi-p4/pikafish.nnue","threads":2,"hashMb":128,"contempt":40}
                panelState.pikafishOptions = {
                    evalFile: auto.pikafish.evalFile == null ? null : String(auto.pikafish.evalFile),
                    threads: auto.pikafish.threads == null ? null : Math.round(Number(auto.pikafish.threads)),
                    hashMb: auto.pikafish.hashMb == null ? null : Math.round(Number(auto.pikafish.hashMb)),
                    contempt: auto.pikafish.contempt == null ? null : Math.round(Number(auto.pikafish.contempt))
                };
                // 面板「避和」按钮要显示实际生效值（autorun 覆盖后同步显示；不在档位表里也照显）
                if (auto.pikafish.contempt != null) {
                    const shown = Math.round(Number(auto.pikafish.contempt));
                    if (isFinite(shown)) {
                        panelState.contemptKey = Math.max(-200, Math.min(200, shown));
                    }
                }
                panelState.engineModule.configurePikafish(panelState.pikafishOptions);
                panelActions.logLine("Pikafish 参数：" + JSON.stringify(panelState.pikafishOptions));
            }
            if (auto.engine) {
                // "auto"（默认）/ "pikafish"（保留键名）—— 两者现在都只走 Pikafish（JS 引擎已移除）
                panelState.engineBackendKey = panelState.engineModule.setBackend(String(auto.engine));
                panelActions.logLine("算棋引擎：" + panelState.engineModule.engineDescription());
            }
            if (auto.observeFrames != null || auto.observeGapMs != null) {
                // 一次观察几帧 / 帧间隔（A/B 用）：1 帧省 ~0.4s/tick，但要盯「确认是不是变慢、误判是不是变多」。
                // 驱动自己会 clamp 到 1~3 帧（见 auto-driver 的 observe 循环）。
                if (auto.observeFrames != null) {
                    panelState.observeFramesKey = Math.max(1, Math.min(3, Math.round(Number(auto.observeFrames) || 2)));
                }
                if (auto.observeGapMs != null) {
                    const gap = Math.round(Number(auto.observeGapMs));
                    panelState.observeGapMsKey = isFinite(gap) && gap >= 0 ? gap : 260;
                }
                panelActions.logLine(
                    "观察帧数：" +
                        panelState.observeFramesKey +
                        " 帧（间隔 " +
                        panelState.observeGapMsKey +
                        "ms；默认 2 帧/260ms）"
                );
            }
            if (auto.debugFrameProbe != null) {
                // 帧生命周期探针（2026-09-21 内存泄漏定位）：观察循环里打「活跃原生帧」检查点。
                panelState.frameProbeOn = !!auto.debugFrameProbe;
                panelActions.logLine("帧探针：" + (panelState.frameProbeOn ? "开（每 tick 打检查点）" : "关"));
            }
            if (auto.replyFastPath != null) {
                // 对方应手「占用唯一解」快路（2026-09-21）：等待对方时用占用扫描直接定那一手，省掉 OCR/跨帧/恢复
                panelState.replyFastPathKey = auto.replyFastPath !== false;
                panelActions.logLine("对方应手快路（占用唯一解）：" + (panelState.replyFastPathKey ? "开" : "关"));
            }
            if (auto.snapPanel === false) {
                // 面板「自己动」的两个来源之一（另一个是抓帧/点击时的挪出屏幕再挪回，见 hidePanel）。
                // 关掉之后面板就停在你放的位置（代价：可能压住棋盘顶行 → 抓帧会改成「藏面板」）。
                panelState.snapPanelEnabled = false;
                panelActions.logLine("自动贴板：关（面板停在你放的位置；默认会贴到棋盘上沿之上那条空带）");
            }
            if (auto.suppressAppBall != null) {
                // 截图前暂避宿主悬浮球：默认关（2026-10-04 用户要求「不用隐藏悬浮球」）；
                // 球可能压住棋盘格子时再写 true 打开。
                panelState.suppressAppBall = !!auto.suppressAppBall;
                panelActions.logLine("宿主悬浮球暂避：" +
                    (panelState.suppressAppBall ? "开（截图前收球）" : "关（不动球）"));
            }
            if (auto.start && panelState.autorunBlocking) {
                panelActions.logLine("autorun：配置有致命错误，未自动开始（修好 autorun 再启动）");
            } else if (auto.start) {
                // 测试钩子：autorun 里带 grid 就直接当几何（无人值守续跑；中局屏幕上线定位易失败时很有用）。
                // ⚠ 面板自己有一份 grid（驱动经 getGrid 端口读它），服务层也有一份 —— 两份都要写；
                //   且 autorun 比 ensureBoardService 早，先 ensure 再 setGrid。
                if (auto.grid && auto.grid.stepX && auto.grid.stepY) {
                    panelActions.ensureBoardService(); // 先建服务（publishGrid 要往里面写）；autorun 比它早
                    panelActions.publishGrid(Object.assign({ orientation: "RED_BOTTOM", version: 1 }, auto.grid));
                    panelActions.logLine(
                        "autorun：用配置里的几何（起点 " +
                            auto.grid.originX +
                            "," +
                            auto.grid.originY +
                            " 格距 " +
                            auto.grid.stepX +
                            "," +
                            auto.grid.stepY +
                            "）"
                    );
                }
                // 2026-09-24：冷启动没有几何时，驱动每 tick 都报「定位由面板负责，请先把 grid 同步进服务」——
                // autorun 先自己把定位校准做掉（auto.locate=false 可显式跳过）。
                let hasGrid = false;
                try {
                    hasGrid = !!(
                        panelState.boardService &&
                        panelState.boardService.grid &&
                        panelState.boardService.grid()
                    );
                } catch (error) {
                    hasGrid = false;
                }
                let geometryOk = true;
                if (!hasGrid && auto.locate !== false) {
                    panelActions.logLine("autorun：还没有几何，先自动定位校准（auto.locate=false 可关）");
                    const located = panelActions.locateAndCalibrate();
                    if (!located || !panelState.grid) {
                        geometryOk = false;
                        panelActions.logLine(panelState.gameOverSeen
                            ? "对局已结束，保持停止；进入新对局后再点「开始」"
                            : "⚠ 自动定位失败，不自动开始；请切到游戏棋盘，再点「定位校准」");
                    } else if (auto.verifyLocate !== false && !panelActions.verifyAutorunGeometry()) {
                        geometryOk = false;
                        panelActions.logLine("⚠ 自动定位后的首帧识别不过 → 几何可疑，不自动开始；请重新定位");
                    }
                }
                if (!geometryOk) {
                    // 保持未运行（本函数尾部照常 render）—— 不拿假几何跑
                } else {
                    panelActions.startLoop();
                    if (auto.declareTurn) {
                        // 刚启动时状态机还没观察过任何局面，assertTurn 会被拒；等第一个观察成功的 tick 再声明
                        panelState.pendingDeclare = true;
                        panelActions.logLine("autorun：首个观察成功后会自动声明「现在轮到我」");
                    }
                    const stopAfterMs = auto.stopAfterMs == null ? 0 : Number(auto.stopAfterMs);
                    if (stopAfterMs > 0) {
                        // 无人值守验证「开始 → 停止」两条路径（watch 模式下不点击棋盘，安全）
                        setTimeout(function () {
                            panelActions.logLine("autorun：到时自动停止（" + stopAfterMs + "ms）");
                            panelActions.stopLoop();
                            panelActions.render();
                        }, stopAfterMs);
                    }
                }
            }
            panelActions.renderParamSliders(); // 参数按钮始终反映当前值（autorun 可能改过 movetime/线程）
            // 崩溃 / 自动重载恢复（2026-09-26）：上次在跑 → 自动继续。
            // 判据放在 autorun 之后：auto.start 已经 startLoop 过的话 running 已是 true，这里自然跳过。
            // Explicit start:false requests a stopped panel, including after a crash.
            if (!panelState.running && !panelState.starting && auto.start !== false && !auto.start && !panelState.autorunBlocking) {
                let wantRun = false;
                try {
                    const store = panelActions.paramStore();
                    wantRun = !!(store && store.get("running", false) === true);
                } catch (error) {
                    wantRun = false;
                }
                if (wantRun) {
                    panelActions.logLine("上次会话在跑（崩溃恢复或自动重载交接）→ 自动继续");
                    panelActions.keepGameInFrontOnce();
                    const resumed = panelActions.startLoop();
                    if (!resumed || resumed.ok !== true) {
                        panelActions.logLine(
                            "自动继续没成功：" +
                                (resumed && resumed.reason ? resumed.reason : "未知原因") +
                                " —— 切到游戏后点「开始」即可"
                        );
                    }
                }
            }
            panelActions.render();
        }
        }, 60);
    }

    return { main: main };
}

module.exports = { create: create };
