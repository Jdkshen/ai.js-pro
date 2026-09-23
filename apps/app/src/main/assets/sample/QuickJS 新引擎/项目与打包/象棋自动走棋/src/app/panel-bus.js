// @engine quickjs
/**
 * 主界面（main.js）↔ 悬浮窗面板（悬浮窗运行与测试.js）的**跨引擎互锁总线**。
 *
 * ## 为什么需要它
 * 两个入口跑在**不同的 JS 引擎**里（页面是 ScriptExecuteActivity 里的一个脚本，面板是另一个
 * 悬浮窗脚本），不能共享内存。原来只有 `src/automation/loop-lock.js` 那把锁：它能拒绝
 * 「两边同时跑」，但**对方在不在跑看不出来、按钮不会变灰、也没法从这边停那边**。
 *
 * ## 三条语义（2026-09-18 用户需求）
 * 1. **状态互锁**：各自把「谁在跑 / 模式 / 我方 / 通道 / 绘制档」写进自己的 state 文件，
 *    对方读得到 → 界面能把「开始观察」变灰并显示「面板正在跑」，反之亦然。
 * 2. **双向停止**：任一方 `stopOther()`，对方在自己的 tick（页面循环）或心跳（面板 1s）里
 *    收到就停；锁的语义不变（仍然是 loop-lock 说了算）。
 * 3. **配置双向同步**：`syncConfig(key, value)` 把 模式/我方/点击通道/截图通道/绘制档
 *    同步到另一边，避免「两边显示不一致、实际按哪边算要看运气」。
 *
 * ## 放在哪
 * `<sdcard>/.aijspro-floaty`（与面板的 autorun 同一个目录）。选它的理由：
 * 两个引擎都读写得到（同一个应用 uid），而且**adb 也能读**——
 * 出问题时 `adb shell cat /sdcard/.aijspro-floaty/bus-state-*.json` 一眼看到两边状态，
 * `bus-command-floaty.json` 还能当无人值守的驱动钩子（与 autorun 一致的口径）。
 *
 * ## 文件
 *   bus-state-main.json / bus-state-floaty.json    各自的状态快照（各写各的，互不覆盖）
 *   bus-command-main.json / bus-command-floaty.json  给某一方的单槽命令（取走即清空）
 */
"use strict";

const labelsModule = require("../ui/labels.js");
const MODE_LABEL = labelsModule.MODE_LABEL;

/** 总线目录：与面板的 autorun 同目录（shell/adb 可读写，便于无人值守与排障）。 */
function resolveDir(files) {
    let sdcard = "/sdcard";
    try {
        if (files && typeof files.getSdcardPath === "function") {
            sdcard = String(files.getSdcardPath());
        }
    } catch (error) {
        // 拿不到就用默认值
    }
    const dir = files.join(sdcard, ".aijspro-floaty");
    try {
        if (typeof files.ensureDir === "function") {
            files.ensureDir(dir + "/");
        }
    } catch (error) {
        // 建不出来就让后面的写失败（调用方按 ok:false 处理，不影响主流程）
    }
    return dir;
}

/**
 * @param options { owner: "main" | "floaty", files, dir?, now?, freshMs?, log? }
 */
function createPanelBus(options) {
    const opts = options || {};
    const owner = String(opts.owner || "main");
    const files = opts.files;
    const now = typeof opts.now === "function" ? opts.now : function () { return Date.now(); };
    const dir = opts.dir || resolveDir(files);
    /** 超过这么久没心跳就认为对面已经不在（脚本退出了），别让界面一直显示"面板在跑"。 */
    const freshMs = opts.freshMs == null ? 8000 : opts.freshMs;
    const other = owner === "main" ? "floaty" : "main";
    const statePath = files.join(dir, "bus-state-" + owner + ".json");
    const otherStatePath = files.join(dir, "bus-state-" + other + ".json");
    const commandPath = files.join(dir, "bus-command-" + owner + ".json");
    const otherCommandPath = files.join(dir, "bus-command-" + other + ".json");
    const log = typeof opts.log === "function" ? opts.log : function () { };

    let seq = 0;
    let lastHandledAt = 0;

    function readJson(path) {
        try {
            const text = String(files.read(path) || "").trim();
            return text ? JSON.parse(text) : null;
        } catch (error) {
            return null;
        }
    }

    function writeJson(path, value) {
        try {
            files.write(path, JSON.stringify(value));
            return true;
        } catch (error) {
            log("写 " + path + " 失败：" + error);
            return false;
        }
    }

    /**
     * 对方的运行状态；没写过或太旧返回 `{ fresh: false }`。
     * `fresh` 很关键：面板脚本被引擎回收后文件还在，不看时间就会一直显示"面板在跑"。
     */
    function readOther() {
        const message = readJson(otherStatePath);
        if (!message || !message.owner) {
            return { owner: other, fresh: false, ageMs: null, state: null };
        }
        const at = Number(message.at || 0);
        const ageMs = at > 0 ? (now() - at) : null;
        return {
            owner: other,
            at: at,
            ageMs: ageMs,
            fresh: ageMs != null && ageMs >= 0 && ageMs < freshMs,
            state: message.state || {}
        };
    }

    /** 把对方状态压成一句人话（两个入口共用，保证两边显示完全一致）。 */
    function describe(read) {
        const who = owner === "main" ? "面板" : "页面";
        if (!read || !read.state) {
            return who + "：没有状态（还没启动过？）";
        }
        if (!read.fresh) {
            const secs = read.ageMs == null ? "?" : Math.round(read.ageMs / 1000);
            return who + "：不在线（最后心跳 " + secs + "s 前）";
        }
        const state = read.state;
        const mode = MODE_LABEL[state.mode] || state.mode || "?";
        const side = state.side === "b" ? "黑方" : "红方";
        // 几何版本与引擎后端也放进这一行：两边界面用同一函数渲染，天然「显示一致」（A1 验收）。
        const grid = (state.gridVersion == null) ? "—" : ("v" + state.gridVersion);
        const backend = state.backend === "pikafish" ? "Pikafish"
            : (state.backend === "js" ? "JS" : (state.backend || "?"));
        const draw = state.drawMode || (state.drawEnabled === true ? "on" : "off");
        return who + "：" + (state.running ? "正在跑" : "未开始") +
            "（" + mode + "，我方：" + side + "，几何 " + grid + "，引擎 " + backend +
            "，绘制：" + draw + "）";
    }

    return {
        owner: owner,
        other: other,
        dir: dir,
        paths: function () {
            return { dir: dir, state: statePath, otherState: otherStatePath, command: commandPath };
        },

        /** 发布本引擎的状态快照（每次调用都刷新心跳时间）。 */
        publish: function (state) {
            return writeJson(statePath, { owner: owner, at: now(), state: state || {} });
        },

        /** 对面状态 + 人话描述。 */
        readOther: readOther,
        describeOther: function () {
            return describe(readOther());
        },

        send: function (to, name, arg) {
            const path = to === other ? otherCommandPath : commandPath;
            seq += 1;
            return writeJson(path, {
                seq: seq, from: owner, to: to, name: name,
                arg: arg === undefined ? null : arg, at: now()
            });
        },
        stopOther: function (why) {
            return this.send(other, "stop", { why: why || (owner === "main" ? "页面点了停止" : "面板点了停止") });
        },
        syncConfig: function (key, value) {
            return this.send(other, "setConfig", { key: key, value: value });
        },

        /** 取走发给我的命令（取到即清空；同一条命令不会执行两次）。 */
        poll: function () {
            const message = readJson(commandPath);
            if (!message || !message.name || message.from === owner) {
                return null;
            }
            writeJson(commandPath, null);
            if (message.at && message.at === lastHandledAt) {
                return null;
            }
            lastHandledAt = message.at || 0;
            return message;
        }
    };
}

module.exports = {
    createPanelBus: createPanelBus,
    resolveDir: resolveDir
};
