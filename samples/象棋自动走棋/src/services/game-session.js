// @engine quickjs
/**
 * 对局会话（§14：创建 driver、搜索任务与停止清理 —— 两处入口共用一份）。
 *
 * 现状：`悬浮窗运行与测试.js` 和自己 `main.js` 各建一套驱动（还有各自的停止清理），
 * 于是"谁的点按起来了、谁忘了释放锁"要靠 `loop-lock` 兜。
 * 这里把"建驱动 + 挂点击租约 + 停干净"固定成一个对象。
 *
 * 这里也是**整手藏窗**接进点击的地方（真机 A/B 的结论，见 runtime/window-manager 注释）：
 *   kind="from" 时藏、kind="to" 之后才恢复；抓帧遇到租约不许抢着恢复。
 */
"use strict";

function createGameSession(options) {
    const opts = options || {};
    const driverModule = opts.driverModule;
    const ports = opts.ports;
    const windows = opts.windows || null;
    const recorder = opts.recorder || null;
    const store = opts.store || null;
    const now = typeof opts.now === "function" ? opts.now : function () { return Date.now(); };

    let driver = null;
    let searchWorker = null; // Retained across configuration/driver rebuilds.
    let running = false;
    let config = Object.assign({}, opts.config || {});
    const note = typeof opts.note === "function" ? opts.note : function () { };

    /** 把窗口租约套在点击上（§ click lease）。 */
    function wrapTap(rawTap) {
        if (!windows || typeof rawTap !== "function") {
            return rawTap;
        }
        return function (x, y, tapOptions) {
            const kind = tapOptions && tapOptions.kind ? String(tapOptions.kind) : "";
            if (kind !== "to") {
                windows.acquireClickLease();
            }
            try {
                return rawTap(x, y, tapOptions);
            } finally {
                if (kind === "to") {
                    windows.releaseClickLease();
                }
            }
        };
    }

    function build(overrides) {
        if (driver) stop("重建驱动");
        const ov = overrides || {};
        const merged = Object.assign({}, config, ov);
        // 依赖组装顺序（A1 批次1）：静态注入（会话创建时给）→ 每次构建的 extras（入口按当前
        // UI/环境给：mySide/screen/nudge/executorConfig…）→ 生命周期项（allowClick/tap/log/config）最后定。
        // extras 让迁移期入口不用改写会话签名，就能把驱动依赖全部收进同一条构建路径；
        // 旧调用点（只传 driverConfig/allowClick）行为不变。
        const deps = {};
        if (typeof opts.recognize === "function") { deps.recognize = opts.recognize; }
        if (typeof opts.locate === "function") { deps.locate = opts.locate; }
        if (typeof opts.analyze === "function") { deps.analyze = opts.analyze; }
        if (typeof opts.foregroundGuard === "function") { deps.foregroundGuard = opts.foregroundGuard; }
        if (typeof opts.log === "function") { deps.log = opts.log; }
        Object.assign(deps, opts.extras || {}, ov.extras || {});
        const allowClick = ov.allowClick === undefined ? (merged.allowClick !== false) : !!ov.allowClick;
        deps.allowClick = allowClick;
        if (typeof deps.log !== "function") {
            deps.log = function (text) { note(text); };
        }
        deps.nowMono = now;
        // 驱动配置：优先 per-build driverConfig；否则用 extras 里直给的 config（迁移期入口）；
        // 都没有才给空对象（旧调用点行为不变）。
        if (merged.driverConfig !== undefined) {
            deps.config = merged.driverConfig;
        } else if (deps.config === undefined) {
            deps.config = {};
        }
        const tap = (ov.tap === undefined) ? opts.tap : ov.tap;
        if (typeof tap === "function" && allowClick) {
            deps.tap = wrapTap(tap);
        }
        if (searchWorker) deps.searchWorker=searchWorker;
        driver = driverModule.createDriver(deps);
        if (driver.searchWorker) searchWorker=driver.searchWorker;
        if (store) {
            store.patch({ computeMode: driver.computeMode ? driver.computeMode() : merged.computeMode }, "driver");
        }
        return driver;
    }

    function start(overrides) {
        // 「已经在跑」只认**驱动真的在运行**：面板/页面把驱动 pause() 之后会话标志还立在，
        // 此时再 start() 应当视为继续（旧写法会误报「已经在跑」，继续按钮失灵）。
        // 驱动没提供 isRunning()（老实现/测试桩）时退回保守判断，保持旧语义。
        if (running && driver) {
            const alive = typeof driver.isRunning === "function" ? driver.isRunning() : true;
            if (alive) {
                return { ok: false, reason: "已经在跑" };
            }
        }
        try {
            if (!driver) {
                build(overrides);
            }
            const state = driver.start();
            running = true;
            if (store) {
                store.patch({ running: true, machineState: state }, "start");
            }
            return { ok: true, state: state };
        } catch (error) {
            running = false;
            return { ok: false, reason: String(error && error.message ? error.message : error) };
        }
    }

    function stop(reason) {
        running = false;
        if (driver && typeof driver.stop === "function") {
            try {
                driver.stop();
            } catch (error) {
                note("停止驱动出错：" + error);
            }
        }
        // 异常路径下窗口可能还藏着：兜底恢复（并解除租约）
        if (windows && typeof windows.ensureVisible === "function") {
            windows.ensureVisible();
        }
        if (store) {
            store.patch({ running: false, machineState: "STOPPED", machineReason: reason || "已停止" }, "stop");
        }
        if (recorder) {
            recorder.event("session-stop", { reason: reason || "已停止" });
        }
        return { ok: true, reason: reason || "已停止" };
    }

    /** 丢弃当前驱动（例如改配置后必须重建：通道在 createTap 时就固定了）。 */
    function discard() {
        if (driver) {
            stop("重建驱动");
        }
        driver = null;
        return true;
    }
    function close() {
        stop("退出会话");
        if (searchWorker && typeof searchWorker.close === "function") searchWorker.close();
        driver=null;searchWorker=null;
    }

    function applyConfig(patch, why) {
        config = Object.assign({}, config, patch || {});
        // 通道/按压这类"创建时确定"的参数变了，必须重建驱动，否则界面显示与实际不符
        const mustRebuild = ["clickMethod", "clickPressMs", "clickHideSettleMs", "computeMode", "gamePackage"]
            .some(function (key) { return patch && patch[key] !== undefined; });
        if (mustRebuild && driver) {
            const wasRunning = running;
            discard();
            note("配置变了（" + (why || "参数更新") + "）→ 驱动已作废，下次启动按新配置重建");
            return { rebuilt: true, wasRunning: wasRunning };
        }
        return { rebuilt: false };
    }

    return {
        build: build,
        start: start,
        stop: stop,
        discard: discard,
        close: close,
        applyConfig: applyConfig,
        driver: function () { return driver; },
        running: function () { return running; },
        config: function () { return Object.assign({}, config); },
        describe: function () {
            return "会话：" + (running ? "运行中" : "未运行") + (driver ? "（驱动已建）" : "（无驱动）");
        }
    };
}

module.exports = { createGameSession: createGameSession };
