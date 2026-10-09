// @engine quickjs
"use strict";

/** driver-loop：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    function buildDriver() {
        const clickAllowed = panelState.modeKey === "auto";
        let tapFunction = null;
        if (clickAllowed && (panelState.adapter.capabilities().tap || panelState.adapter.capabilities().shizuku)) {
            // 通道名非法 → createTap 直接抛错：宁可停在这里，也不要偷檟切回 auto 污染对照实验。
            let tapper;
            try {
                tapper = panelState.adapter.createTap({
                    pressMs: panelState.clickPressMs,
                    method: panelState.clickMethodKey
                });
            } catch (error) {
                panelActions.logLine("点击通道不可用：" + error + "（检查 autorun 里的 clickMethod）");
                throw error;
            }
            tapFunction = tapper.tap;
            panelActions.logLine(
                "点击请求=" +
                    panelState.clickMethodKey +
                    "，实际=" +
                    tapper.method +
                    "，按压=" +
                    panelState.clickPressMs +
                    "ms（无障碍=" +
                    (panelState.adapter.capabilities().accessibilityReady ? "就绪" : "未就绪") +
                    "，Shizuku=" +
                    (panelState.adapter.capabilities().shizuku ? "可用" : "不可用") +
                    "），脚本=" +
                    panelState.PROBE_BUILD_ID
            );
            // ⚠️ **整手藏面板**（真机 A/B 结论，2026-09-14）：
            //   同一抬手手势、同一通道、同一坐标 —— 单手探针（整轮藏面板）**即时生效、吃子也成**；
            //   自动循环却"点击无效"：先 SENT，确认阶段连续 UNCHANGED，跑完再截图棋子其实到位了。
            //   进一步对照后定位到：**两次点击之间把面板显示出来**会让游戏丢掉"已选中"状态
            //   （窗口增删 → 焦点变化 → 选中被取消），于是落点那一点只是又选中了目标子。
            //   所以按执行器的 kind 做租约：kind="from" 时藏、kind="to" 之后才恢复。
            // 兜底：如果 "to" 没来（失败/取消/暂停），loop 停止和下一次 "from" 都会把面板恢复回来。
            const rawTap = tapper.tap;
            let clickLeaseHidden = false;
            function acquireClickLease() {
                if (clickLeaseHidden) {
                    return;
                }
                // 2026-09-24（提前）：面板**不压棋盘**时跳过藏窗 —— 租约要藏的原因是「窗口增删会丢游戏选中」
                // （见上方 A/B 注释）；而注点全在棋盘上、面板根本没遮住它们时，藏+显示各一次窗口操作
                // 纯属白花时间（~0.2s/手）且同样是「两次点击之间动窗口」。判不出来（棋盘未知）仍保守藏。
                let overlap = null;
                try {
                    overlap = panelActions.rectsOverlap(panelActions.panelRect(), panelActions.boardRect());
                } catch (error) {
                    overlap = null;
                }
                if (overlap === false) {
                    clickLeaseHidden = false;
                    panelState.clickLeaseActive = false;
                    return;
                }
                try {
                    if (panelActions.setActivePanelVisibility(false, "点击租约") !== false) {
                        clickLeaseHidden = true;
                        panelState.clickLeaseActive = true;
                        // 藏窗到出手的等待：真机 A/B 显示"藏完立刻点"可能还没被游戏接纳
                        // （探针是藏窗 → 定位 → 抓帧，好几秒后才出手，一点就中）。
                        sleep(panelState.clickHideSettleMs);
                    }
                } catch (error) {
                    clickLeaseHidden = false;
                    panelState.clickLeaseActive = false;
                }
            }
            function releaseClickLease() {
                if (!clickLeaseHidden) {
                    panelState.clickLeaseActive = false;
                    return;
                }
                clickLeaseHidden = false;
                panelState.clickLeaseActive = false;
                try {
                    panelActions.setActivePanelVisibility(true);
                } catch (error) {
                    panelActions.logLine("点击后恢复面板失败：" + error);
                }
            }
            tapFunction = function (x, y, options) {
                const kind = options && options.kind ? String(options.kind) : "";
                if (kind !== "to") {
                    // 起点点击（或没标 kind 的调用）：先藏，整手期间保持隐藏
                    acquireClickLease();
                }
                try {
                    return rawTap(x, y, options);
                } finally {
                    if (kind === "to") {
                        releaseClickLease();
                    }
                }
            };
            // 暴露给停止/退出路径，避免异常路径下面板一直藏着
            panelState.releaseTapLease = releaseClickLease;
        }
        // A1「入口收口」批次1：驱动不再由面板直建 —— 统一走会话（bootstrap 装配）；
        // 依赖仍由面板提供（保留 wrapDriverDeps 计时），构建/停止/资源所有权收在会话一处。
        panelState.driver = panelActions.ensureApp().session.build({
            allowClick: clickAllowed,
            tap: tapFunction,
            extras: panelActions.wrapDriverDeps({
                recognizeAsync: panelActions.observeAsync,
                recognize: function (options) {
                    // ★ 入口收口（2026-09-20）：这条链的编排（抓帧藏窗 → 稳定帧 → 流水线 → 规则门 → 不可信回退旧路径）
                    //   全在 `services/board-service.js`；面板只负责注入平台能力并把结果交给绘制。
                    //   以前这里自己写了一遍，且回退分支会在「不藏面板」的路径上抓帧 —— 真机踩过
                    //   （同一帧「校准（会藏）未知 0」而「驱动观察（不藏）未知 23」）。服务层用的是注入的抓帧，
                    //   一律走 `grabFrame()`。
                    const service = panelActions.ensureBoardService();
                    const obsStarted = Date.now();
                    // 识别链只有字形识别（2026-09-24「其他的都不要了」）：差分/流水线/旧路径分支全部移除；
                    // 读不过的帧服务层会返回 ok:false，驱动按「这一帧不观察」处理。
                    const smart = service.recognizeSmart(options || {});
                    if (panelState.frameProbeOn) {
                        panelActions.logLine("[帧探针] 全盘识别后 " + panelActions.frameProbeActive());
                    }
                    // 账本：一次观察里驱动会调 2 次（两帧合并）—— 分开记，才看得出「其他时间」在哪
                    panelState.tickObsCalls += 1;
                    panelState.tickObsMs += Date.now() - obsStarted;
                    if (smart && smart.ok) {
                        panelState.lastPipelineUnknown = smart.unknownCells || 0;
                        panelActions.refreshOverlay(smart, null);
                        panelActions.logPipelineNote(smart);
                    }
                    // ★ 2026-09-24 真机（和棋那局）：结算页会让字形识别整体失败（双方帅/将读不出来）——
                    //   探针原来挂在上面 ok 分支里 ⇒ 永远等不到 ⇒ 对局结束后循环一直 WAIT_STABLE 空转，
                    //   浮窗一直显示「等稳定」（用户看到的就是「没有变成未运行」）。
                    //   失败帧现在也带 frame（board-service 里补的），有图就探。
                    if (panelActions.probeGameOver(smart && smart.frame && smart.frame.image, false,
                        !!(smart && !smart.ok && !smart.unstable))) {
                        return { ok: false, reason: "对局结算页：停止观察和点击", frame: smart && smart.frame };
                    }
                    // `ok:false` / `unstable` 都当作「这一帧不观察」：驱动计一次未识别、不会乱推轮次
                    return smart || null;
                },
                locate: function () {
                    const locateStarted = Date.now();
                    const result = panelActions.locateAndCalibrate();
                    panelState.tickLocateCalls += 1;
                    panelState.tickLocateMs += Date.now() - locateStarted;
                    return result ? { ok: true, grid: panelState.grid } : { ok: false, reason: "定位/校准失败" };
                },
                analyze: function (position, options) {
                    return panelState.engineModule.analyze(position, options);
                },
                isTrustworthy: function (value) {
                    // 阀值 0.40：与 piece-detector 的 maxCellDistance(0.38) 对齐 —— 网格偏零点几格不影响
                    // 判色/落点（两者都锚在检测框上），而「整行偏」由 grid-sanity 体检负责。
                    return panelState.geometryModule.isTrustworthy(value, { minSamples: 4, maxResidual: 0.4 });
                },
                screen: function () {
                    return panelState.adapter.screenSize();
                },
                foregroundGuard: panelActions.foregroundGuard,
                onMovePlan: function (plan) {
                    panelActions.refreshOverlay(plan.result, null, { movePlan:plan });
                },
                // 催游戏（见 nudgeGame）：驱动发现「已知事实连着好几帧读成空」时会调这里 ——
                // 那不是模型漏检，而是画面卡在走子动画中间（人机对战，动画本该 1 秒内走完）。
                nudge: panelActions.nudgeGame,
                // 运行中只用字形识别；不再注入 PP-OCR 单格/全盘/ROI 的第二套识别能力。
                // 驱动缺少辅助证据时走保守的全盘确认，不用另一种识别器改写盘面。
                log: function (text) {
                    panelActions.logLine(text);
                },
                // 崩溃前哨（2026-09-26）：驱动把 tick 的关键分段报上来（进 logcat，不上屏）
                stage: function (name) {
                    panelActions.stageMark(name);
                },
                config: {
                    recordGames: true,
                    observeOnly: panelState.modeKey === "watch",
                    tickMs: 2400, // screencap 通道 0.6~1.4s/帧；而且**游戏落子有动画** ——
                    // K40 实测 tick=1400 时，点击后第一次确认会看到「还没动」的旧帧 → 误判 UNCHANGED
                    // → 状态机走恢复、把轮次基准弄丢 → 非开局局面就再也推不出轮次。所以放大到 2400。
                    // 算棋预算：默认 5000ms；autorun `movetimeMs` 可覆盖。
                    // K40 + Pikafish 实测：1200ms ≈ 深度 18~19（原生引擎），快一截换来的是整手节奏。
                    movetimeMs: panelState.movetimeMsKey,
                    adaptiveSearch: require("../session-settings.js").read().adaptiveSearch,
                    // ★ 开局库步数（2026-09-25 用户）：前 N 手查开局库（命中秒出），之后引擎接手算棋
                    bookOwnMoves: panelState.bookOwnMovesKey,
                    // ★ 开局库候选随机（2026-09-25 用户：「每次都一样，搞随机的吧」）
                    bookRandom: panelState.bookRandomKey,
                    // ★ 求胜模式（2026-09-25 用户：「老是和棋」）：均势时避开会走回旧局面的着法（默认关）
                    winSeek: panelState.winSeekKey,
                    criticalReview: require("../session-settings.js").read().criticalReview,
                    prethink: require("../session-settings.js").read().prethink,
                    openingLearning: require("../session-settings.js").read().openingLearning,
                    // ★ 2026-10-06：对面应手预判档位（策略菜单：关/快 500×3/标准 900×5/深 1500×5）——
                    //   「标准」就是原来写死的 900ms × 5 路；此处随 panelState 档位键走。
                    predictReply: require("../session-settings.js").replyOptions(panelState.replyGearKey).predictReply,
                    predictReplyMovetimeMs: require("../session-settings.js").replyOptions(panelState.replyGearKey).movetimeMs,
                    predictReplyMultiPv: require("../session-settings.js").replyOptions(panelState.replyGearKey).multiPv,
                    searchDepth: panelState.searchModeKey === "depth" ? panelState.searchDepthKey : 0,
                    depthTimeoutMs: panelState.depthTimeoutMsKey,
                    // ★ 2026-09-21 提速（方案第 3、4 条）：
                    //   · 等待对方时观察降为 1 帧（出手前/确认/恢复仍 2 帧）；
                    //   · 点击后先走「起点/终点两格」ROI 快速确认，拿不出结论才全盘确认。
                    observeFramesByState: true,
                    fastConfirmRoI: true,
                    modelVersion: "chessai_160x320_v5r",
                    confidence: 0.001,
                    orientation: "RED_BOTTOM",
                    autoAlign: panelState.autoAlignOn,
                    // 算棋默认放独立引擎线程（worker）：不然一步 ~1.2s 的搜索会把面板触摸一起挡掉。
                    // autorun 里写 "computeMode":"inline" 可以对照老路径。
                    computeMode: panelState.computeModeKey,
                    // 引擎后端与 Pikafish 参数：worker 与 inline 两条路都会带过去（见 compute/computeInline）。
                    engineKey: panelState.engineBackendKey,
                    pikafish: panelState.pikafishOptions,
                    // 一次观察看几帧/间隔多少（autorun 可覆盖，做 A/B 用；驱动默认 2 帧 + 260ms）
                    observeFrames: panelState.observeFramesKey,
                    observeGapMs: panelState.observeGapMsKey,
                    // ★ 对方应手「占用唯一解」快路（2026-09-21）：diff 链路的 occupancy/changed 直接定手，省 OCR
                    replyFastPath: panelState.replyFastPathKey
                },
                // 两击之间的间隔：太短会「只选中、没落子」（选中动画还没画完）。
                // ⚠️ 这**不是**之前「一手棋全 UNCHANGED」的原因 —— 真因是**前台是本应用**（点击被脚本宿主吃掉），
                // 已由 foregroundGuard 在看。这里保持适度间隔即可。
                // press 已等待手势完成；只给选中动画留 240ms，避免重复等待。
                executorConfig: {
                    pressMs: panelState.clickPressMs,
                    settleMs:
                        panelState.clickSettleMsKey != null
                            ? panelState.clickSettleMsKey
                            : 240
                }
            })
        });
        panelState.driver.setMySide(panelState.mySideKey || "w");
        return panelState.driver;
    }

    function tick() {
        if (!panelState.driver) {
            return;
        }
        if (showPaused()) return;
        panelActions.stageMark("tick:begin");
        const tickStarted = Date.now();
        panelState.tickObsCalls = 0;
        panelState.tickObsMs = 0;
        panelState.tickAsyncObsMs = 0;
        panelState.tickLocateCalls = 0;
        panelState.tickLocateMs = 0;
        panelState.tickGuardMs = 0;
        panelState.tickDriverMs = 0;
        try {
            // ★ 先把游戏留在前台再观察：透明宿主的窗口一拿到焦点，游戏就 pause → 对方永不应手
            //   （真机证据见 keepGameInFront 的注释）。点击租约期间不动，免得抦断正在发的手势。
            if (!panelState.clickLeaseActive && (panelState.modeKey === "auto" || panelState.modeKey === "advice")) {
                const guardStarted = Date.now();
                panelActions.keepGameInFront();
                panelState.tickGuardMs += Date.now() - guardStarted;
            }
            panelActions.stageMark("tick:guard-done");
            const driverStarted = Date.now();
            panelState.depAccum = {};
            const result = panelState.driver.tick();
            panelState.tickDriverMs = Date.now() - driverStarted;
            panelActions.stageMark("tick:driver-done");
            // ★ 对局结算页（probeGameOver 在观察里命中）：等驱动这轮走完再停，不在 tick 中途掉驱动
            if (result && result.stopRequired) {
                panelActions.stopLoop();
                panelActions.logLine("自动走棋已停止：" + result.reason + "。确认棋盘后再开始。");
                return;
            }
            if (panelState.gameOverSeen) {
                panelState.gameOverSeen = false;
                panelActions.stopLoop();
                panelActions.logLine("对局已结束，自动走棋已停。点「再来一局」后再点「开始」即可继续。");
                return;
            }
            // Verification can pause inside driver.tick(). No observation happened
            // after that pause: don't report '?' or run the blind relocation timer.
            if (showPaused()) return;
            const depTop = Object.keys(panelState.depAccum)
                .map(function (name) {
                    return { name: name, calls: panelState.depAccum[name].calls, ms: panelState.depAccum[name].ms };
                })
                .sort(function (a, b) {
                    return b.ms - a.ms;
                })
                .slice(0, 4)
                .map(function (d) {
                    return d.name + " " + d.ms + "ms×" + d.calls;
                })
                .join("｜");
            panelState.depAccum = null;
            const machine = panelState.driver.machine;
            const stats = panelState.driver.stats();
            // Search completion, cancellation and state changes can happen without a new
            // recognition callback. Refresh once the tick has finished accepting its results.
            panelActions.refreshOverlay(panelState.driver.lastResult(), null);
            // 互锁续期：锁被别人（主界面）抢走就果断停，绝不让两个引擎同时跑
            if (!panelState.loopLock.touch(panelState.loopLock.OWNER_FLOATY)) {
                const now = panelState.loopLock.status();
                panelActions.logLine("循环锁被「" + (now.owner || "?") + "」拿走，面板循环退出。");
                panelActions.stopLoop();
                return;
            }
            if (result && result.observing) return;
            // ★ 2026-09-24 真机（用户报「又识别不到了」）：坏几何被采用后每帧读不到局面，
            //   而状态机停在 WAIT_STABLE —— 没有任何看门狗会去重定位 ⇒ 永远空转。
            // ★ 2026-09-25 真机（K40：一格读不出 → 卡 19 分钟）："读不到局面" 不能只看「观察失败」——
            //   未知格>0 的观察同样是“成功的”，但它让状态机永远等不到干净帧。
            //   所以计时看 **最近一次「未知格 0」的观察**（首次/还没见过干净帧时回退到 lastObsOkAt）。
            //   限频 20s；连碰 3 次都不行就给用户一句可操作的提示（否则只会看到一直重定位）。
            if (!panelState.lastObsOkAt) {
                panelState.lastObsOkAt = Date.now();
            }
            // ★ 2026-09-26 修复（真机：24s 长思考的局，看门狗**每步棋**都误报一次「连续 26s 没有
            //   一次「未知格 0」」并强制重定位）：
            //   算棋期间所有 tick 都带 `thinking`（无截图、无观察）⇒ lastCleanObsAt 从「算棋前那一眼」
            //   开始变老；movetime=24s + 链上开销 ⇒ 算完那一 tick 恰好越过 25s 阈值 ⇒ 每逢算棋必误报。
            //   修法：把「算棋进行中」也算作「忙、别动几何」——thinking tick 刷新 lastThinkBusyAt，
            //   stall 基准取 max(干净观察, 忙)。
            if (result && result.thinking) {
                panelState.lastThinkBusyAt = Date.now();
            }
            const stallBase = Math.max(
                panelState.lastCleanObsAt > 0 ? panelState.lastCleanObsAt : panelState.lastObsOkAt,
                panelState.lastThinkBusyAt
            );
            if (
                panelState.running &&
                !(result && result.thinking) &&
                Date.now() - stallBase > 25000 &&
                Date.now() - panelState.lastBlindRelocateAt > 20000
            ) {
                panelState.lastBlindRelocateAt = Date.now();
                panelState.blindStuckCount += 1;
                const nowUnknown = result && result.unknownCells != null ? result.unknownCells : "?";
                panelActions.logLine(
                    "连续 " +
                        Math.round((Date.now() - stallBase) / 1000) +
                        "s 没有一次「未知格 0」的观察（本轮未知 " +
                        nowUnknown +
                        "）→ 强制重新定位（盲读兜底）"
                );
                // ★ 25s+ 连一次「未知格 0」都没有 ⇒ 旧几何已不可用（见 gridCondemnAt 说明）：
                //   先判它可疑再重定位，否则重定位会被 0.45 格连续性护栏挡住（真机 16:16 卡 200s 的铁证）。
                if (!panelActions.gridCondemned() && panelState.grid) {
                    panelState.gridCondemnAt = Date.now();
                    panelActions.logLine("旧几何 25s+ 读不出干净盘面 ⇒ 判为可疑：本轮重定位跳过连续性护栏");
                }
                const relocated = panelActions.locateAndCalibrate();
                panelActions.logLine(relocated ? "盲读兜底：重新定位完成" : "盲读兜底：这次没换成几何，之后再试");
                if (panelState.blindStuckCount >= 3) {
                    panelActions.logLine(
                        "注意：已连续 " +
                            panelState.blindStuckCount +
                            " 次因未知格读不出而重定位。若一直卡在同一格：" +
                            "把那一格的棋子动一下（手动走一步）、或点「定位校准」重采几何"
                    );
                }
                panelActions.render();
            }
            // 算棋中：本轮没截图、只 poll 了一次 worker 结果 → 只按秒报一次，不刷屏
            if (result && result.thinking) {
                const now = Date.now();
                if (now - panelState.lastThinkingLogAt >= 1000) {
                    panelState.lastThinkingLogAt = now;
                    panelActions.logLine("状态 " + machine.state() + "｜" + result.reason);
                }
                return;
            }
            if (panelState.frameProbeOn) {
                panelActions.logLine("[帧探针] tick 开始 活跃 " + panelActions.frameProbeActive());
            }
            panelActions.logLine("状态 " + machine.state() + "｜" + machine.reason());
            if (result) {
                // 驱动实际看到的局面（排障用）：只在与上一轮不同时打印，避免刷屏
                const view = panelState.driver.lastResult();
                const layout = view && view.layout ? view.layout : null;
                if (layout && layout !== panelState.lastLoggedLayout) {
                    panelState.lastLoggedLayout = layout;
                    const counted = view.chars ? panelState.cellsContract.count(view.chars) : null;
                    panelActions.logLine(
                        "驱动看到：子 " +
                            (counted ? counted.pieces : "?") +
                            " 空 " +
                            (counted ? counted.empty : "?") +
                            " 未知 " +
                            (counted && counted.unknown != null ? counted.unknown : "?") +
                            (counted && counted.ok ? "" : "（不合规：" + (counted ? counted.reason : "?") + "）")
                    );
                    panelActions.logLine("  局面 " + layout);
                }
            }
            if (result && result.ok) {
                panelState.lastObsOkAt = Date.now(); // 盲读兜底计时：只要有一次成功观察就续期
                // 干净帧（未知格 0）才计到 lastCleanObsAt：状态机能出手的前提，见文件头那段说明
                if (result.unknownCells === 0) {
                    panelState.lastCleanObsAt = Date.now();
                    panelState.blindStuckCount = 0;
                }
                if (panelState.pendingDeclare) {
                    const declared = panelState.driver.assertTurn();
                    panelActions.logLine("autorun 声明轮到我：" + declared.reason);
                    // ⚠️ 被拒（局面还有未知格）时必须**留着**这个标记、下一轮干净帧再试：
                    //    真机 2026-09-20 踩到 —— 一轮没声明成，之后就再无基准，
                    //    驱动一路 RECOVERING 空转到 stopAfterMs（日志里只有「轮次未知超时」）。
                    panelState.pendingDeclare = !declared.ok;
                }
                // 驱动只保证给「观察到的局面/未知格」；不同版本字段不完全一样，所以逐项判空
                const unknown = result.unknownCells == null ? "?" : result.unknownCells;
                panelActions.logLine(
                    "本轮观察：未知格 " +
                        unknown +
                        "，耗时 " +
                        stats.lastObserveMs +
                        "ms" +
                        (result.layout ? "，局面 " + result.layout : "")
                );
                // 未知格具体在哪几格（只在位置串变化时打一行）：排障时「只写个数」不够
                if (result.unknownCells > 0) {
                    try {
                        const view = panelState.driver.lastResult();
                        const chars = view && view.chars ? view.chars : null;
                        if (chars) {
                            const spots = [];
                            for (let i = 0; i < chars.length && spots.length < 8; i++) {
                                if (chars[i] === "?") {
                                    spots.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")");
                                }
                            }
                            const text = spots.join(" ");
                            if (text && text !== panelState.lastUnknownSpots) {
                                panelState.lastUnknownSpots = text;
                                panelActions.logLine("  未知格位置：" + text);
                            }
                        }
                    } catch (error) {
                        // 排障信息，拿不到就算
                    }
                }
                // 耗时分期（方案 §7）：把「一轮观察 7~11s」拆开 —— 抓帧 / 稳定帧 / 流水线内（检测・逐格・抢救・规则）
                if (Date.now() - panelState.lastTimingLogAt >= 2000) {
                    panelState.lastTimingLogAt = Date.now();
                    try {
                        const timingModule = panelActions.load("src/runtime/timing.js");
                        const view = panelState.driver.lastResult();
                        const detail = view && view.timing ? view.timing : panelActions.ensureBoardService().lastTiming();
                        if (detail && detail.smart) {
                            panelActions.logLine(
                                timingModule.format(detail.smart, "耗时分期（最近一次识别）") +
                                    (detail.pipeline ? "｜流水线内 " + timingModule.format(detail.pipeline) : "") +
                                    (detail.legacy ? "｜已退回旧路径" : "")
                            );
                        }
                    } catch (error) {
                        // 计时不该影响主流程
                    }
                }
            } else if (result) {
                panelActions.logLine("本轮没出手：" + result.reason);
            }
            const ticket = machine.currentTicket();
            if (ticket) {
                panelActions.logLine(
                    "计划：" +
                        ticket.moveICCS +
                        "（" +
                        (panelState.searchModeKey === "depth"
                            ? "目标 " + panelState.searchDepthKey + " / 实际 "
                            : "深度 ") +
                        (ticket.depth == null ? "—（引擎未回深度）" : ticket.depth) +
                        "，评分 " +
                        (ticket.score == null ? "?" : Math.round(ticket.score)) +
                        "，" +
                        ticket.executionState +
                        "）"
                );
            }
            // —— 本轮账（方案 §7：抓帧降下来之后，「其他时间」到底花在哪）——
            //   本轮池 = 整次 tick 的墙钟；识别 N 次 = 驱动两帧合并调了几次、共多少 ms；
            //   剩下的大头通常是：算棋 worker（THINKING 轮次会提前 return）、点击/确认（另开一次观察）、
            //   点击/确认及心跳调度。
            if (Date.now() - panelState.lastAccountLogAt >= 2000) {
                panelState.lastAccountLogAt = Date.now();
                panelActions.logLine(
                    "本轮账：tick " +
                        (Date.now() - tickStarted) +
                        "ms｜守前台 " +
                        panelState.tickGuardMs +
                        "ms｜驱动 " +
                        panelState.tickDriverMs +
                        "ms（识别 " +
                        panelState.tickObsCalls +
                        " 次共 " +
                        panelState.tickObsMs +
                        "ms｜重定位 " +
                        panelState.tickLocateCalls +
                        " 次共 " +
                        panelState.tickLocateMs +
                        "ms）｜依赖 top " +
                        (depTop || "无") +
                        "｜驱动自计观察 " +
                        (stats.lastObserveMs || 0) +
                        "ms" +
                        (panelState.tickAsyncObsMs ? "｜后台观察 " + panelState.tickAsyncObsMs + "ms（不占本线程）" : "") +
                        (result && result.thinking ? "｜（算棋中）" : "")
                );
            }
            panelActions.stageMark("tick:end");
        } catch (error) {
            panelActions.logLine(
                "自动循环出错：" +
                    error +
                    (error && error.stack ? "｜堆栈：" + String(error.stack).split("\n").slice(0, 4).join(" ← ") : "")
            );
            panelActions.stopLoop();
        }
    }

    function showPaused() {
        const machine = panelState.driver.machine;
        if (machine.state() !== "PAUSED") {
            panelState.lastPausedReason = null;
            return false;
        }
        const reason = machine.reason();
        if (panelState.lastPausedReason !== reason) {
            panelState.lastPausedReason = reason;
            panelActions.logLine("状态 PAUSED｜" + reason + "（暂停期间不采集新帧）");
            panelActions.refreshOverlay(panelState.driver.lastResult(), null);
            panelActions.render();
        }
        return true;
    }

    return { buildDriver: buildDriver, tick: tick };
}

module.exports = { create: create };
