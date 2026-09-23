// @engine quickjs
/**
 * 启动装配（§14：入口最终只保留路径解析 + bootstrap + 创建 View + 启动与退出挂接）。
 *
 * 两个入口（主页面 main.js / 悬浮窗面板）都走这里，差别只在传进来的 View 与 handlers：
 *   · 页面 UI 由 ui/page-layout + page-view 提供；
 *   · 悬浮窗 UI 由 ui/floaty-layout + floaty-view 提供。
 * 装配顺序固定：**先解析配置 → 再建端口 → 再建业务**（通道在 createTap 时就固定了，
 * 先建驱动再改配置会得到"界面显示 A、实际走 B"的假象）。
 */
"use strict";

const configModule = require("./config.js");
const controllerModule = require("./controller.js");
const sessionStoreModule = require("./session-store.js");
const ownerChannelModule = require("./owner-channel.js");
const portsModule = require("../runtime/ports.js");
const windowManagerModule = require("../runtime/window-manager.js");
const recorderModule = require("../diagnostics/recorder.js");
const boardServiceModule = require("../services/board-service.js");
const gameSessionModule = require("../services/game-session.js");
const viewModel = require("./view-model.js");

/**
 * @param options {
 *   owner, adapter, host: { files, context, images, win, sleep, now, readFile, writeFile },
 *   modules: { driverModule, locateBoard, recognizeBoard, geometry, loopLock },
 *   paths: { autorun, command, state, frameDir },
 *   panel: { height, width },
 *   handlers: { START, STOP, ... }（由入口提供，业务细节仍归入口）
 * }
 */
function createApp(options) {
    const opts = options || {};
    const host = opts.host || {};
    const modules = opts.modules || {};
    const paths = opts.paths || {};

    // ---- 1) 记录器（最先建：后面每一步都要能记日志）----
    const recorder = recorderModule.createRecorder({
        sink: typeof opts.sink === "function" ? opts.sink : function () { },
        now: host.now
    });
    const log = recorder.log;

    // ---- 2) 配置：读 autorun（唯一碰文件的地方）+ 校验 ----
    const readFile = typeof host.readFile === "function" ? host.readFile
        : (host.files && typeof host.files.read === "function" ? function (p) { return host.files.read(p); } : null);
    const writeFile = typeof host.writeFile === "function" ? host.writeFile
        : (host.files && typeof host.files.write === "function" ? function (p, t) { host.files.write(p, t); } : null);

    const autorun = paths.autorun
        ? configModule.loadAutorun(readFile, paths.autorun)
        : { ok: true, config: {}, issues: [], raw: null, empty: true, path: null };

    if (autorun.path) {
        log("autorun 文件 " + autorun.path + (autorun.empty ? "（不存在或为空）" :
            " 原文 " + String(autorun.text || "").replace(/\s+/g, " ")));
    }
    const issues = autorun.issues || [];
    for (let i = 0; i < issues.length; i++) {
        const item = issues[i];
        log("配置" + (item.level === "error" ? "错误" : "提示") + "：" + item.key + " — " + item.message);
    }
    const blocking = configModule.hasBlockingIssue(issues);
    const config = configModule.withDefaults(autorun.config);
    if (blocking) {
        log("配置里有致命错误 → 按约定**不启动**（避免拼错的通道名污染对照实验）");
    }

    // ---- 3) 端口：声明 + 组装 + 能力核对 ----
    const ports = portsModule.createPorts(opts.adapter, {
        now: host.now, sleep: host.sleep, readFile: readFile, writeFile: writeFile,
        files: host.files, context: host.context, images: host.images, win: host.win
    });
    log(ports.describe());
    const missing = ports.missing();
    if (missing.length > 0) {
        log("**缺少必需的宿主能力：" + missing.join("、") + "** —— 截图/取像素这类基础动作会失败");
    }

    // ---- 4) 状态 ----
    const store = sessionStoreModule.createSessionStore({
        mode: config.mode,
        channel: config.clickMethod,
        pressMs: config.clickPressMs,
        hideSettleMs: config.clickHideSettleMs,
        computeMode: config.computeMode,
        drawMode: config.drawMode,
        captureBackend: config.captureBackend
    });

    // ---- 5) 窗口 ----
    const windows = windowManagerModule.createWindowManager({
        win: host.win,
        sleep: host.sleep,
        panel: opts.panel || {},
        clickHideSettleMs: config.clickHideSettleMs
    });

    // ---- 6) 棋盘服务 + 对局会话 ----
    // `boardOverrides`（可选）：入口的平台特异项（自定义抓帧端口、定位/校准实现、视觉流水线注入等）。
    // 装配顺序仍归 bootstrap，入口只提供零件 —— A1「入口收口」的迁移口。
    const boards = boardServiceModule.createBoardService(Object.assign({
        ports: ports,
        locateBoard: modules.locateBoard,
        recognizeBoard: modules.recognizeBoard,
        geometry: modules.geometry,
        recorder: recorder,
        captureBackend: config.captureBackend,
        hideWindow: function (fn) { return windows.withHidden(fn); },
        note: log
    }, opts.boardOverrides || {}));

    const session = gameSessionModule.createGameSession({
        driverModule: modules.driverModule,
        ports: ports,
        windows: windows,
        recorder: recorder,
        store: store,
        config: config,
        note: log,
        recognize: opts.recognize,       // 由入口提供（要藏窗、要复用 frames）
        locate: opts.locate,
        analyze: opts.analyze,
        tap: opts.tap,
        foregroundGuard: opts.foregroundGuard,
        extras: opts.driverExtras || null   // 入口的驱动附加依赖（isTrustworthy/screen/…），A1 迁移口
    });

    // ---- 7) 单运行者 + 跨引擎通道 ----
    const owner = ownerChannelModule.createOwnerChannel({
        owner: opts.owner || "floaty",
        lock: modules.loopLock,
        writeFile: writeFile,
        readFile: readFile,
        commandPath: paths.command,
        statePath: paths.state,
        now: host.now
    });

    // ---- 8) 控制器 ----
    const controller = controllerModule.createController({
        handlers: opts.handlers || {},
        store: store,
        recorder: recorder,
        // 「在跑」以**驱动实际状态**为准：面板/页面把驱动 pause() 后会话标志还立着，
        // 此时 START 应当放行（继续），PAUSE/RESUME 的守卫也才和真实状态一致（A1 批次2）。
        isRunning: function () {
            const d = session.driver();
            if (!d) { return session.running(); }
            return typeof d.isRunning === "function" ? d.isRunning() : session.running();
        }
    });

    // ---- 9) 退出钩子：停业务、还锁、清窗口 ----
    let disposed = false;
    function dispose(reason) {
        if (disposed) {
            return { ok: true, already: true };
        }
        disposed = true;
        try {
            if (session.running()) {
                session.stop(reason || "退出");
            }
        } catch (error) {
            log("退出时停止会话出错：" + error);
        }
        try {
            owner.release();
        } catch (error) {
            log("退出时释放锁出错：" + error);
        }
        windows.destroy();
        return { ok: true };
    }

    function registerExitHook(onExit) {
        try {
            if (host && typeof host.events !== "undefined" && host.events && typeof host.events.on === "function") {
                host.events.on("exit", function () { dispose("脚本退出"); });
            }
        } catch (error) {
            log("注册退出钩子失败：" + error);
        }
        if (typeof onExit === "function") {
            onExit(dispose);
        }
    }

    return {
        config: config,
        autorun: autorun,
        blocking: blocking,
        ports: ports,
        recorder: recorder,
        log: log,
        store: store,
        windows: windows,
        boards: boards,
        session: session,
        owner: owner,
        controller: controller,
        viewModel: viewModel,
        registerExitHook: registerExitHook,
        dispose: dispose,
        describe: function () {
            return [ports.describe(), boards.describe(), session.describe(), windows.describe()].join("｜");
        }
    };
}

module.exports = { createApp: createApp };
