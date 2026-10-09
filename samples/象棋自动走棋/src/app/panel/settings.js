// @engine quickjs
"use strict";
const settings = require("../session-settings.js");
function create(s, a) {
    let stored = null;
    function refreshSharedSettings() {
        const next = settings.read();
        const keys = settings.changed(stored, next);
        if (!keys.length) return;
        const first = !stored;
        stored = next;
        // 仅应用真正改变的键，保留本次 autorun 的非持久化覆盖。
        const map = { adaptiveSearch: "adaptiveSearchKey", thinkMs: "movetimeMsKey", thinkStepMs: "thinkStepKey", searchMode: "searchModeKey",
            searchDepth: "searchDepthKey", depthTimeoutMs: "depthTimeoutMsKey", bookOwnMoves: "bookOwnMovesKey",
            bookRandom: "bookRandomKey", winSeek: "winSeekKey", hashMb: "hashMbKey", contempt: "contemptKey",
            replyGear: "replyGearKey" };
        keys.forEach(function (k) { if (map[k]) s[map[k]] = next[k]; });
        if (keys.indexOf("mySide") >= 0 && s.mySideKey !== next.mySide) a.setPanelSide(next.mySide);
        if ((keys.indexOf("mode") >= 0 && s.modeKey !== next.mode) ||
            (keys.indexOf("tap") >= 0 && s.clickMethodKey !== next.tap)) {
            if (s.running || s.starting) a.stopLoop();
            if (keys.indexOf("mode") >= 0) s.modeKey = next.mode;
            if (keys.indexOf("tap") >= 0) s.clickMethodKey = next.tap;
            if (!first) a.logLine("对局设置已更新，点开始使用新模式/通道。");
        }
        const enginePatch = {};
        ["threads", "hashMb", "contempt"].forEach(function (k) { if (keys.indexOf(k) >= 0) enginePatch[k] = next[k]; });
        s.pikafishOptions = Object.assign({}, s.pikafishOptions || {}, enginePatch);
        if (Object.keys(enginePatch).length) s.engineModule.configurePikafish(enginePatch);
        // 从实际面板状态生成驱动参数，避免覆盖 autorun 的独立权重/线程。
        if (s.driver) {
            settings.applyDriver(s.driver, Object.assign({}, next, { thinkMs: s.movetimeMsKey,
                searchMode: s.searchModeKey, searchDepth: s.searchDepthKey, depthTimeoutMs: s.depthTimeoutMsKey,
                bookOwnMoves: s.bookOwnMovesKey, bookRandom: s.bookRandomKey, winSeek: s.winSeekKey,
                replyGear: s.replyGearKey,
                threads: s.pikafishOptions.threads || 2, hashMb: s.hashMbKey, contempt: s.contemptKey }));
            s.driver.setPikafish(s.pikafishOptions);
        }
        a.renderParamSliders();
        if (!first) a.render();
    }
    return { refreshSharedSettings: refreshSharedSettings };
}
module.exports = { create: create };
