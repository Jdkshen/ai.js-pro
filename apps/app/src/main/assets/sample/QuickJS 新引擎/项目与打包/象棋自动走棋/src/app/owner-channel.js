// @engine quickjs
/**
 * 单运行者 + 跨引擎命令/状态通道（§14.5）。
 *
 * 背景：主页面（main.js）与悬浮窗面板跑在**不同的 JS 引擎**里，不能共享内存。
 * 现状是两边各建一套驱动 + 用 `src/automation/loop-lock.js` 互斥，但：
 *   · 谁在跑只能靠锁文件猜；
 *   · 想从页面"让面板停一下"没有通道；
 *   · 两边各自显示的状态可能不一致。
 * 这里在锁之上加一个**文件消息通道**（命令 + 状态快照），并保持锁的语义不变。
 */
"use strict";

function createOwnerChannel(options) {
    const opts = options || {};
    const owner = opts.owner || "floaty";
    const lock = opts.lock || null;
    const writeFile = opts.writeFile || null;
    const readFile = opts.readFile || null;
    const commandPath = opts.commandPath || null;
    const statePath = opts.statePath || null;
    const now = typeof opts.now === "function" ? opts.now : function () { return Date.now(); };

    function writeJson(path, value) {
        if (!path || typeof writeFile !== "function") {
            return false;
        }
        try {
            writeFile(path, JSON.stringify(value, null, 2));
            return true;
        } catch (error) {
            return false;
        }
    }

    function readJson(path) {
        if (!path || typeof readFile !== "function") {
            return null;
        }
        try {
            const text = String(readFile(path) || "").trim();
            return text ? JSON.parse(text) : null;
        } catch (error) {
            return null;
        }
    }

    return {
        owner: owner,
        /** 抢占"谁在跑"（沿用 loop-lock 的语义，成功才算）。 */
        claim: function () {
            if (!lock) {
                return true;
            }
            try {
                return !!lock.acquire(owner);
            } catch (error) {
                return false;
            }
        },
        release: function () {
            if (!lock) {
                return true;
            }
            try {
                return !!lock.release(owner);
            } catch (error) {
                return false;
            }
        },
        isHeld: function () {
            if (!lock) {
                return false;
            }
            try {
                return lock.isHeld ? !!lock.isHeld() : false;
            } catch (error) {
                return false;
            }
        },
        lockPath: function () {
            return lock && typeof lock.lockPath === "function" ? lock.lockPath() : null;
        },

        /** 给另一个引擎发命令（会被 poll 消费一次）。 */
        send: function (name, arg) {
            return writeJson(commandPath, { from: owner, name: name, arg: arg || null, at: now() });
        },
        /** 取走待执行命令（取到即清空，避免重复执行）。 */
        poll: function () {
            const message = readJson(commandPath);
            if (!message || !message.name) {
                return null;
            }
            if (message.from === owner) {
                return null;   // 自己发的别自己收
            }
            writeJson(commandPath, null);
            return message;
        },
        /** 发布本引擎的状态快照（界面/审计可以显示"另一边在干什么"）。 */
        publish: function (snapshot) {
            return writeJson(statePath, { owner: owner, at: now(), state: snapshot });
        },
        readState: function () {
            const message = readJson(statePath);
            return message && message.owner !== owner ? message : null;
        },
        paths: function () {
            return { command: commandPath, state: statePath };
        }
    };
}

module.exports = { createOwnerChannel: createOwnerChannel };
