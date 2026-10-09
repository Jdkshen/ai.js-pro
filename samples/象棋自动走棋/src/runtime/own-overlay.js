// @engine quickjs
"use strict";

/** 只暂避宿主的悬浮球；循环结束后恢复原本显示的球，避免把图标读成棋子。 */
function create(host) {
    if (typeof host.acquire === "function" && typeof host.release === "function") {
        let grant = null;
        return {
            acquire: function () {
                if (grant) return false;
                const next = host.acquire();
                if (!next || !(next.token > 0)) throw new Error("应用悬浮球暂避未获得租约");
                grant = next;
                return next.changed === true;
            },
            release: function () {
                if (!grant) return;
                host.release(grant.token);
                grant = null;
            }
        };
    }
    let held = false, restore = false;
    return {
        acquire: function () {
            // 旧宿主的页面恢复可能重新打开球；每次抓帧复查，避免一直沿用过期状态。
            const showing = host.showing();
            if (showing) host.hide();
            restore = restore || showing;
            held = true;
            return showing;
        },
        release: function () {
            if (!held) return;
            held = false;
            const needed = restore;
            restore = false;
            if (needed) host.show();
        }
    };
}

function createHost(log) {
    let disabled = false;
    const manager = Packages.com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
    let supportsTokens = false;
    try { supportsTokens = typeof manager.isCircularMenuSuppressed() === "boolean"; }
    catch (error) { /* 旧宿主保留兼容路径，完整的多任务避让需要新宿主。 */ }
    const lease = create(supportsTokens ? {
        acquire: function () {
            let grant = null;
            ui.run(function () {
                const changed = manager.isCircularMenuShowing() && !manager.isCircularMenuSuppressed();
                grant = { token: Number(manager.acquireCircularMenuSuppression()), changed: changed };
            });
            return grant;
        },
        release: function (token) {
            ui.run(function () { manager.releaseCircularMenuSuppression(token); });
        }
    } : {
        showing: function () { return Packages.com.jdkshen.aijspro.ui.floating.FloatyWindowManger.isCircularMenuShowing(); },
        hide: function () { ui.run(function () { Packages.com.jdkshen.aijspro.ui.floating.FloatyWindowManger.hideCircularMenu(); }); },
        show: function () { ui.run(function () { Packages.com.jdkshen.aijspro.ui.floating.FloatyWindowManger.showCircularMenuIfNeeded(); }); }
    });
    function invoke(name) {
        if (disabled && name === "acquire") return false;
        try { return lease[name](); }
        catch (error) {
            disabled = true;
            if (log) log("应用悬浮球暂避不可用：" + error);
            return false;
        }
    }
    return { acquire: function () { return invoke("acquire"); }, release: function () { return invoke("release"); } };
}

module.exports = { create: create, createHost: createHost };
