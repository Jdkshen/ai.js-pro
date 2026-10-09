// @engine quickjs
/**
 * 窗口管理器（§14：窗口位置、隐藏租约和退出销毁集中在这里）。
 *
 * 这些规则都是真机踩出来的，散在面板里时每加一个功能就要重想一遍：
 *
 *  1) **抓帧要藏窗**：面板浮在棋盘上，不藏的话上几行子会被自己的窗口盖住
 *     （实测：同一帧「校准（会藏）未知 0」而「驱动观察（不藏）未知 23」）。
 *  2) **点棋要藏窗、而且要整手都藏**：两次点击之间把窗口显示出来，游戏会丢掉"已选中"
 *     （探针全程藏 = 一点就中；循环只在单次点击前后藏 = 连点 5 次全 UNCHANGED）。
 *  3) **租约期间抓帧不许"恢复"窗口**：否则等于在两次点击之间又动了一次窗口，回到 2) 的坑。
 *  4) **贴板只向上**：定位抖动时往下压会盖住棋盘顶排；用户拖过就不再抢位置。
 */
"use strict";

function createWindowManager(options) {
    const opts = options || {};
    const win = opts.win;
    const sleep = typeof opts.sleep === "function" ? opts.sleep : function () { };
    const panel = opts.panel || { height: 372 };
    const gapAboveBoard = opts.gapAboveBoard == null ? 10 : opts.gapAboveBoard;
    const hideSettleMs = opts.hideSettleMs == null ? 90 : opts.hideSettleMs;

    let hidden = false;             // 我们自己请求的隐藏状态
    let leaseHeld = false;          // 点击租约（整手持有时为 true）
    let hiddenByUs = false;
    let userMoved = false;          // 用户拖过 → 不再自动贴板
    let top = null;
    let left = null;
    const events = [];

    function remember(text) {
        events.push({ at: Date.now(), text: text });
        if (events.length > 50) {
            events.shift();
        }
    }

    function setVisible(visible) {
        if (!win || typeof win.setVisibility !== "function") {
            return false;
        }
        try {
            const ok = win.setVisibility(visible) !== false;
            hidden = !visible;
            return ok;
        } catch (error) {
            remember("设置窗口可见性失败：" + error);
            return false;
        }
    }

    function hide() {
        if (hidden) {
            return true;
        }
        const ok = setVisible(false);
        hiddenByUs = ok;
        return ok;
    }

    function show() {
        return setVisible(true);
    }

    /** 抓帧用：藏 → 跑 fn → 恢复（**租约期间不恢复**，见类注释 3）。 */
    function withHidden(fn, settleMs) {
        const wasHidden = hidden;
        let didHide = false;
        if (!wasHidden) {
            didHide = hide();
            if (didHide) {
                sleep(settleMs == null ? hideSettleMs : settleMs);
            }
        }
        try {
            return fn();
        } finally {
            if (didHide && !leaseHeld) {
                show();
            }
        }
    }

    /**
     * 点击租约：整手（起点点击 → 落点点击）期间保持隐藏。
     * @param settleMs 藏完到出手的等待（真机 A/B：探针是藏完数秒才出手，一点就中）
     */
    function acquireClickLease(settleMs) {
        if (leaseHeld) {
            return true;
        }
        const ok = hide();
        if (ok) {
            leaseHeld = true;
            sleep(settleMs == null ? opts.clickHideSettleMs == null ? 120 : opts.clickHideSettleMs : settleMs);
        }
        return ok;
    }

    function releaseClickLease() {
        if (!leaseHeld) {
            return false;
        }
        leaseHeld = false;
        return show();
    }

    /** 兜底：停止/退出/异常路径调用，别把面板留在隐藏状态。 */
    function ensureVisible() {
        leaseHeld = false;
        return show();
    }

    /**
     * 贴到棋盘上沿之上（只向上）。
     * @param box 棋盘外框 { y }（屏幕坐标）
     * @param screenHeight 屏幕高
     * @param defaultRatio 默认位置比例（拿不到棋盘时用）
     */
    function snapAboveBoard(box, screenHeight, defaultRatio) {
        if (!win || typeof win.setPosition !== "function") {
            return { moved: false, reason: "窗口不支持 setPosition" };
        }
        const ratio = defaultRatio == null ? 0.11 : defaultRatio;
        const screen = screenHeight && screenHeight > 0 ? screenHeight : 0;
        const baseTop = screen > 0 ? Math.round(screen * ratio) : null;
        let target = baseTop;
        if (box && box.y > 0) {
            const above = Math.round(box.y) - gapAboveBoard - panel.height;
            target = (baseTop == null) ? above : Math.min(baseTop, above);
        }
        if (target == null) {
            return { moved: false, reason: "没有可用的屏幕高度或棋盘位置" };
        }
        if (userMoved) {
            return { moved: false, reason: "用户拖动过，不抢位置" };
        }
        if (top != null && Math.abs(target - top) < 2) {
            return { moved: false, reason: "已在目标位置" };
        }
        const previous = top;
        try {
            win.setPosition(left == null ? win.x || 0 : left, target);
        } catch (error) {
            // 有的宿主只支持 setPosition(x, y) 之外的形式：退回直接改属性
            try {
                win.y = target;
            } catch (inner) {
                return { moved: false, reason: "设置位置失败：" + inner };
            }
        }
        top = target;
        return { moved: true, from: previous, to: target };
    }

    function markUserMoved() {
        userMoved = true;
        try {
            if (win && typeof win.x === "number") {
                left = win.x;
            }
            if (win && typeof win.y === "number") {
                top = win.y;
            }
        } catch (error) {
            // 读不到就算了，只影响后续自动贴板
        }
    }

    /** 退出销毁（宿主窗口自己会随脚本结束关闭，这里只做状态清理）。 */
    function destroy() {
        leaseHeld = false;
        hidden = false;
        events.length = 0;
    }

    return {
        withHidden: withHidden,
        acquireClickLease: acquireClickLease,
        releaseClickLease: releaseClickLease,
        ensureVisible: ensureVisible,
        snapAboveBoard: snapAboveBoard,
        markUserMoved: markUserMoved,
        show: show,
        hide: hide,
        destroy: destroy,
        isHidden: function () { return hidden; },
        isLeaseHeld: function () { return leaseHeld; },
        isUserMoved: function () { return userMoved; },
        describe: function () {
            return "窗口：隐藏=" + (hidden ? "是" : "否") + "，点击租约=" + (leaseHeld ? "持有" : "无") +
                "，用户拖过=" + (userMoved ? "是" : "否");
        },
        events: function () { return events.slice(); }
    };
}

module.exports = { createWindowManager: createWindowManager };
