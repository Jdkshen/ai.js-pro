// @engine quickjs
"use strict";

/** board-service：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    function ocrRuntime() {
        return panelState.ocrHolder.get();
    }

    function releaseOcr() {
        panelState.ocrHolder.release();
    }

    /**
     * 建（或取）识别服务实例：把平台能力注入服务层，**编排逻辑全在服务层**。
     * 定位/校准仍由面板自己管（它有 pickGrid 兜底与校准流程），只需把 grid 同步进服务。
     */
    /**
     * A1「入口收口」批次1：应用装配改由 bootstrap 统一（棋盘服务 + 会话）。
     * 面板只提供平台特异项：抓帧端口 / 定位桩 / 旧路径包装 / 视觉流水线注入 —— 行为参数原样。
     * 可回退：还原本函数体为旧的 createBoardService 直建即可。
     */
    function ensureApp() {
        if (!panelState.app) {
            ensureBoardService(); // app 与 boardService 同源创建
        }
        return panelState.app;
    }

    /** 浮窗按钮可能在首帧定位失败后被点击；先装配控制器，失败只记状态，不让事件回调杀掉脚本。 */
    function runPanelCommand(name, arg) {
        try {
            if (panelState.starting && name !== "STOP" && name !== "START") {
                return { ok: false, reason: "准备中；请先取消再使用工具" };
            }
            const current = ensureApp();
            if (!current || !current.controller) {
                throw new Error("控制器尚未就绪");
            }
            const result = current.controller.run(name, arg);
            if (result && result.ok === false) {
                panelActions.logLine(name + " 未执行：" + (result.reason || "条件不满足"));
            }
            return result;
        } catch (error) {
            panelActions.logLine(name + " 失败：" + error);
            return { ok: false, reason: String(error) };
        }
    }

    function ensureBoardService() {
        if (panelState.boardService) {
            return panelState.boardService;
        }
        if (!panelState.visionInjection.gate) {
            panelState.visionInjection.gate =
                panelState.boardFrameGate ||
                (panelState.boardFrameGate = panelState.frameGateModule.create({ log: function () {} }));
        }
        // （2026-09-24「其他的都不要了」）不再预热视觉流水线：识别链只有字形识别，YOLO/OCR 会话不再预建。
        panelState.app = panelState.bootstrapModule.createApp({
            owner: "floaty",
            bus: panelState.bus, // A1 批次3：装配与入口共用同一总线实例
            lockOwner: panelState.loopLock.OWNER_FLOATY, // 退出还锁的令牌（与面板 acquire 口径一致）
            adapter: panelState.adapter,
            host: {
                files: typeof files !== "undefined" ? files : null,
                context: typeof context !== "undefined" ? context : null,
                images: typeof images !== "undefined" ? images : null,
                win: null, // 面板窗口的显隐仍由面板自己管（点击租约/抓帧）
                sleep: typeof sleep === "function" ? sleep : null
            },
            modules: {
                driverModule: panelState.driverModule,
                locateBoard: panelState.locateBoard,
                recognizeBoard: panelState.recognizeBoard,
                geometry: panelState.geometryModule,
                loopLock: panelState.loopLock
            },
            paths: {}, // autorun 由面板自己读（不用装配器的配置加载）
            sink: function (text) {
                panelActions.logLine(text);
            },
            // A1 批次2：状态性命令统一走控制器（参数/运行态校验 → handler → 记录事件）
            handlers: {
                START: function () {
                    return panelActions.startLoop();
                },
                STOP: function () {
                    panelActions.stopLoop();
                    return { ok: true };
                },
                RELOCATE: function () {
                    return panelActions.locateAndCalibrate()
                        ? { ok: true }
                        : { ok: false, reason: "定位失败；确认游戏棋盘正在屏幕上" };
                },
                RECOGNIZE: function () {
                    return panelActions.recognizeOnce()
                        ? { ok: true }
                        : { ok: false, reason: "识别失败；先确认棋盘可见并重新定位" };
                },
                SELFTEST: function () {
                    panelActions.runSelfCheck();
                    return { ok: true };
                },
                CONTRACT: function () {
                    panelActions.runContractTests();
                    return { ok: true };
                },
                NEW_SESSION: function () {
                    panelActions.newSession();
                    return { ok: true };
                },
                DECLARE_TURN: function (arg) {
                    if (!panelState.driver) {
                        return { ok: false, reason: "先开始（或先识别一帧）再声明轮次" };
                    }
                    const result = panelState.driver.assertTurn();
                    panelActions.logLine("声明轮到我：" + result.reason);
                    return result && result.ok === false ? { ok: false, reason: result.reason } : { ok: true };
                }
            },
            boardOverrides: {
                checkGeometry: panelState.locateBoard.checkGeometry,
                ports: {
                    now: function () {
                        return Date.now();
                    },
                    // ★ 2026-09-25「对齐 Auto.js Pro 的截图语义」（小预算版，死等已撤销）：
                    //   · 纯等对方落子（状态机 reason = “轮到对方…”）⇒ grabSettledFrame：
                    //     轻量探测「画面动没动」（预算 800ms），捕捉到对手落子就等动画停再给帧；
                    //   · 其余状态（含「轮次未知/轮次待佐证」）⇒ 只等静止。待佐证那一步必须要
                    //     「再看一眼同一张静止画面」，所以那类状态绝不做变化探测。
                    capture: function () {
                        const m = panelState.driver && panelState.driver.machine ? panelState.driver.machine : null;
                        if (m && typeof m.awaitingOpponentMove === "function" && m.awaitingOpponentMove()) {
                            return panelActions.grabSettledFrame({ allowAbort: true });
                        }
                        return panelActions.grabStableFrame();
                    }
                },
                locateBoard: {
                    locateBoardInImage: function () {
                        // 定位由面板负责（locateAndCalibrate 含 pickGrid 兜底与校准），服务层不自己定位
                        return { ok: false, reason: "定位由面板负责，请先把 grid 同步进服务" };
                    }
                },
                vision: panelState.visionInjection,
                glyph: panelState.glyphSource,
                note: function (text) {
                    panelActions.logLine(text);
                }
            }
        });
        panelState.boardService = panelState.app.boards;
        if (panelState.grid) {
            panelActions.publishGrid(panelState.grid);
        }
        return panelState.boardService;
    }

    return {
        ocrRuntime: ocrRuntime,
        releaseOcr: releaseOcr,
        ensureApp: ensureApp,
        runPanelCommand: runPanelCommand,
        ensureBoardService: ensureBoardService
    };
}

module.exports = { create: create };
