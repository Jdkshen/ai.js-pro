// @engine quickjs
"use strict";
const settings = require("../app/session-settings.js");
const colors = require("./page-layout.js").COLORS;
function selected(view, on) {
    view.setTextColor(on ? "#FFFFFF" : colors.text);
    view.setBackgroundColor(on ? colors.blue : colors.panelAlt);
}
function create(ui, onChange, report) {
    let current = settings.read();
    function render() {
        selected(ui.searchTime, current.searchMode === "time");
        selected(ui.searchDepth, current.searchMode === "depth");
        ui.thinkValue.setText(current.searchMode === "depth" ? current.searchDepth + " 层" :
            (current.adaptiveSearch ? "自动 ≤ " : "") + (current.thinkMs / 1000).toFixed(1) + " 秒");
        ui.threadsValue.setText(String(current.threads));
        ui.openingValue.setText(current.bookOwnMoves + " 手");
        ui.thinkStep.setText("步长 · " + (current.thinkStepMs / 1000).toFixed(1) + " 秒");
        ui.thinkStep.setEnabled(current.searchMode === "time");
        ui.depthTimeout.setText("深度超时 · " + current.depthTimeoutMs / 1000 + " 秒");
        ui.depthTimeout.setEnabled(current.searchMode === "depth");
        ui.hashSize.setText("缓存 · " + current.hashMb + " MB");
        ui.contempt.setText("避和 · " + current.contempt);
        ui.bookRandom.setText("开局随机 · " + (current.bookRandom ? "开" : "关"));
        ui.winSeek.setText("求胜 · " + (current.winSeek ? "开" : "关"));
        selected(ui.winSeek, current.winSeek); selected(ui.bookRandom, current.bookRandom);
        const time = current.searchMode === "time";
        ui.thinkMinus.setEnabled(time ? current.thinkMs > 500 : current.searchDepth > 6);
        ui.thinkPlus.setEnabled(time ? current.thinkMs < 20000 : current.searchDepth < 24);
        ui.threadsMinus.setEnabled(current.threads > 1); ui.threadsPlus.setEnabled(current.threads < 8);
        ui.openingMinus.setEnabled(current.bookOwnMoves > 0); ui.openingPlus.setEnabled(current.bookOwnMoves < 12);
    }
    function accept(next) {
        const keys = settings.changed(current, next); current = next;
        if (keys.length) { render(); onChange(current, keys); }
    }
    function update(patch) {
        const saved = settings.write(patch); accept(saved.value);
        if (!saved.ok) report("设置暂时生效，但未保存：" + saved.reason);
    }
    function cycle(key, options) {
        const latest = settings.read();
        const patch = {}; patch[key] = options[(options.indexOf(latest[key]) + 1) % options.length]; update(patch);
    }
    function step(key, delta) { const patch = {}; patch[key] = settings.read()[key] + delta; update(patch); }
    ui.searchTime.on("click", function () { update({searchMode:"time"}); });
    ui.searchDepth.on("click", function () { update({searchMode:"depth"}); });
    ui.thinkStep.on("click", function () { cycle("thinkStepMs", settings.OPTIONS.thinkStepMs); });
    ui.depthTimeout.on("click", function () { cycle("depthTimeoutMs", settings.OPTIONS.depthTimeoutMs); });
    ["Minus", "Plus"].forEach(function (suffix) {
        const dir = suffix === "Minus" ? -1 : 1;
        ui["think"+suffix].on("click", function () {
            const v = settings.read(); step(v.searchMode === "depth" ? "searchDepth" : "thinkMs", dir * (v.searchMode === "depth" ? 1 : v.thinkStepMs));
        });
        ui["threads"+suffix].on("click", function () { step("threads", dir); });
        ui["opening"+suffix].on("click", function () { step("bookOwnMoves", dir); });
    });
    ui.hashSize.on("click", function () { cycle("hashMb", settings.OPTIONS.hashMb); });
    ui.contempt.on("click", function () { cycle("contempt", [0,40,80,120,160,200]); });
    ui.winSeek.on("click", function () { update({winSeek:!settings.read().winSeek}); });
    ui.bookRandom.on("click", function () { update({bookRandom:!settings.read().bookRandom}); });
    render();
    return { refresh: function () { accept(settings.read()); }, update: update, value: function () { return current; } };
}
module.exports = { create: create, selected: selected };
