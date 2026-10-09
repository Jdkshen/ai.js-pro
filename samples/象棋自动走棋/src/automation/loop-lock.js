// @engine quickjs
/**
 * 棋盘循环的**跨引擎互锁**。
 *
 * 为什么需要：主界面（main.js）和悬浮窗面板（悬浮窗运行与测试.js）是**两个独立引擎**，
 * 各自都有一套「开始观察/开始」按钮。互不通知的话两边可以同时跑：两个引擎一起截图、
 * 一起算棋，模式③下还会**双份点击棋盘**（真机上就是要命的事）。
 *
 * 做法：一个带心跳的锁文件（放应用缓存目录，两个引擎都能算出同一路径）：
 *   · 开始前 acquire(owner) —— 别人占着（且心跳还新鲜）就拒绝并说明是谁；
 *   · 跑着的时候每轮 touch(owner) 续期；
 *   · 停止/关窗/退出时 release(owner)；
 *   · 持有者进程被杀 → 心跳停 → TTL 过期后别人可以接管（不会永久卡死）。
 *
 * 纯文件操作，不依赖宿主别的能力；拿不到 files 时退化成「不加锁」（返回 ok，并在 reason 里说明）。
 */
"use strict";

const LOCK_NAME = "xiangqi-loop.lock";
/** 心跳过期时间：比一个 tick 长得多，又短到「进程被杀后不用等太久」 */
const TTL_MS = 12000;

const OWNER_MAIN = "主界面";
const OWNER_FLOATY = "悬浮窗面板";

function filesApi() {
    return (typeof files !== "undefined" && files) ? files : null;
}

function lockPath() {
    const io = filesApi();
    if (!io) {
        return "";
    }
    try {
        if (typeof context !== "undefined" && context && typeof context.getCacheDir === "function") {
            return io.join(String(context.getCacheDir()), LOCK_NAME);
        }
    } catch (error) {
        // 退回 sdcard
    }
    try {
        if (typeof io.getSdcardPath === "function") {
            return io.join(String(io.getSdcardPath()), LOCK_NAME);
        }
    } catch (error) {
        // 用兜底路径
    }
    return "/sdcard/" + LOCK_NAME;
}

function readRaw() {
    const io = filesApi();
    if (!io) {
        return null;
    }
    try {
        const text = String(io.read(lockPath()) || "").trim();
        if (!text) {
            return null;
        }
        const parsed = JSON.parse(text);
        return (parsed && parsed.owner) ? parsed : null;
    } catch (error) {
        return null;
    }
}

function writeRaw(owner) {
    const io = filesApi();
    if (!io) {
        return false;
    }
    try {
        io.write(lockPath(), JSON.stringify({ owner: String(owner), at: Date.now() }));
        return true;
    } catch (error) {
        return false;
    }
}

function holder(nowMs) {
    const raw = readRaw();
    if (!raw) {
        return null;
    }
    const at = Number(raw.at || 0);
    const now = nowMs == null ? Date.now() : nowMs;
    if (at > 0 && (now - at) > TTL_MS) {
        return null;   // 心跳过期：持有者大概已经不在了
    }
    return raw;
}

/** 当前锁状态（给界面显示用）。 */
function status() {
    const held = holder(Date.now());
    return {
        held: !!held,
        owner: held ? String(held.owner) : null,
        ageMs: held ? (Date.now() - Number(held.at || 0)) : 0,
        path: lockPath(),
        available: !!filesApi()
    };
}

/**
 * 抢锁。@returns {{ok, owner?, reason?, path}}
 */
function acquire(owner, options) {
    const opts = options || {};
    const me = String(owner);
    const io = filesApi();
    if (!io) {
        return { ok: true, owner: me, reason: "运行时没有 files，未启用互锁", path: "" };
    }
    const held = holder(Date.now());
    if (held && String(held.owner) !== me && opts.force !== true) {
        return {
            ok: false,
            owner: String(held.owner),
            reason: "棋盘循环已经在「" + held.owner + "」里跑着（互锁）",
            path: lockPath()
        };
    }
    writeRaw(me);
    return { ok: true, owner: me, path: lockPath() };
}

/** 续期心跳；被别人抢走了返回 false（调用方应当停止循环）。 */
function touch(owner) {
    const me = String(owner);
    const held = holder(Date.now());
    if (held && String(held.owner) !== me) {
        return false;
    }
    return writeRaw(me);
}

/** 释放（只有持有者能释放）。 */
function release(owner) {
    const me = String(owner);
    const io = filesApi();
    if (!io) {
        return false;
    }
    const raw = readRaw();
    if (raw && String(raw.owner) !== me) {
        return false;
    }
    try {
        io.remove(lockPath());
        return true;
    } catch (error) {
        return false;
    }
}

module.exports = {
    LOCK_NAME: LOCK_NAME,
    TTL_MS: TTL_MS,
    OWNER_MAIN: OWNER_MAIN,
    OWNER_FLOATY: OWNER_FLOATY,
    lockPath: lockPath,
    status: status,
    acquire: acquire,
    touch: touch,
    release: release
};
