// @engine quickjs
"use strict";
// PC-only: exercise cancellation, cleanup, mode isolation and view update cost.
const assert = require("assert");
const flow = require("../src/app/panel/start-flow.js");
const pageXml = require("../src/ui/page-layout.js").build("test",{});
const pageSource = require("fs").readFileSync(require("path").join(__dirname,"../main.js"),"utf8");
for (const match of pageSource.matchAll(/ui\.([A-Za-z]+)\.(?:on|set\w+|get\w+)\(/g)) {
    assert(pageXml.includes('id="'+match[1]+'"'),"Missing page binding: "+match[1]);
}
const realTimeout = global.setTimeout, realClear = global.clearTimeout;
let jobs, next;
function harness(mode) {
    jobs = new Map(); next = 0;
    global.setTimeout = fn => { jobs.set(++next, fn); return next; };
    global.clearTimeout = id => jobs.delete(id);
    const calls = [], store = {};
    const state = { modeKey: mode || "watch", UI_MODE_LABEL: { watch: "只识别", advice: "建议", auto: "自动" },
        loopLock: { OWNER_FLOATY: "floaty", acquire: () => { calls.push("lock"); return {ok:true}; } },
        engineModule: { warmUpPikafish: () => { calls.push("warm"); return {ok:true}; } } };
    const actions = {
        render() {}, logLine() {}, stageMark() {}, pumpBus() {},
        keepGameInFrontOnce() { calls.push("front"); },
        foregroundGuard() { calls.push("guard"); return {ok:true}; },
        locateAndCalibrate() { calls.push("locate"); state.grid = {}; return true; },
        buildDriver() { calls.push("build"); state.app = { session: { start() { calls.push("session"); return {ok:true}; } } }; },
        markPanelRunning(on) { calls.push("mark:"+on); },
        scheduleNextTick(ms) { calls.push("tick:"+ms); },
        stopLoop() { actions.clearStartFeedback(); state.running = false; calls.push("release"); },
        paramStore() { return { put(k,v) { store[k] = v; } }; }
    };
    Object.assign(actions, flow.create(state,actions));
    function one() { const [id,fn] = jobs.entries().next().value; jobs.delete(id); fn(); }
    function drain() { let count=0; while(jobs.size) { assert(++count<20); one(); } }
    return {state,actions,calls,store,one,drain};
}
try {
    let h = harness();
    assert.strictEqual(h.actions.startLoop().pending,true);
    assert.deepStrictEqual(h.calls,["lock"],"slow work ran before first UI yield");
    assert.strictEqual(h.actions.startLoop().ok,false,"duplicate start accepted");
    const stale = [...jobs.values()][0];
    h.actions.stopLoop(); stale(); h.drain();
    assert(!h.calls.includes("session"),"cancelled job restarted");
    h.actions.startLoop(); h.drain();
    assert(h.state.running && h.calls.includes("tick:60"));
    assert(!h.calls.includes("warm"),"watch mode warmed the engine");
    h = harness();
    h.actions.locateAndCalibrate = () => { h.actions.stopLoop(); return true; };
    h.actions.startLoop(); h.drain();
    assert(!h.calls.includes("session") && !h.state.running, "stop during native callback was overwritten");
    h = harness("auto"); h.actions.startLoop(); h.drain();
    assert(h.calls.indexOf("guard") < h.calls.indexOf("session"));
    assert(h.calls.includes("warm"));
    for (const stage of ["keepGameInFrontOnce","locateAndCalibrate","foregroundGuard","buildDriver"]) {
        h = harness("auto"); h.actions[stage] = () => {throw new Error("broken:"+stage);};
        h.actions.startLoop(); h.drain();
        assert(!h.state.starting && !h.state.running && h.calls.includes("release"));
        assert(h.state.startError.includes(stage));
        assert(!h.calls.includes("session"));
    }
    h = harness("advice"); h.state.engineModule.warmUpPikafish = () => ({ok:false,reason:"no engine"});
    h.actions.startLoop(); h.drain();
    assert.strictEqual(h.state.startError,"no engine"); assert(h.calls.includes("release"));
    h = harness(); h.state.loopLock.acquire = () => ({ok:false,reason:"page running"});
    assert.strictEqual(h.actions.startLoop().ok,false); assert.strictEqual(jobs.size,0);
    h.actions.setPanelSide("b"); assert.strictEqual(h.state.mySideKey,"b"); assert.strictEqual(h.store.mySide,"b");
    const viewActions = require("../src/ui/panel/window.js").create({},{});
    let writes = 0;
    const view = {setText(){writes++;}};
    for (let i=0;i<100;i++) viewActions.setPanelText("status",view,"ready");
    assert.strictEqual(writes,1);
    viewActions.setPanelText("status",{setText(){writes++;}},"ready");
    assert.strictEqual(writes,2,"replacement view did not receive initial state");
    // The watch branch never calls compute/predict/tap, including a recognizable own-turn board.
    const loop = require("../src/automation/driver/loop.js");
    let observed = 0;
    const driverState = {running:true,stats:{ticks:0},stage(){},cfg:{observeOnly:true},clickAllowed:false,
        grid:{},machine:{state(){return "READY";}}};
    const driverActions = new Proxy({observeOnce(){observed++;return {ok:true,chars:[],unknownCells:0};}},
        {get(o,k){if(k in o)return o[k];throw new Error("watch entered "+k);}});
    assert(loop.create(driverState,driverActions).tick().ok); assert.strictEqual(observed,1);
    const calibration = require("../src/vision/grid-calibration.js");
    let scans = 0;
    const calibrator = calibration.create(() => { scans++; return {ok:true,unknownCells:0,layout:"4k4/9/9/9/9/9/9/9/9/4K4",validation:{ok:true}}; });
    const cancelled = calibrator.calibrateGrid({}, {ok:true,grid:{originX:30,originY:40,stepX:40,stepY:40}}, {
        shouldAbort(){return scans >= 2;}
    });
    assert(cancelled.aborted && !cancelled.ok && !cancelled.grid, "partial search published a candidate");
    scans = 0;
    const fast = calibrator.calibrateGrid({}, {ok:true,grid:{originX:30,originY:40,stepX:40,stepY:40}}, {
        acceptBaseGrid(){return true;}
    });
    assert(fast.earlyAccept && scans === 1, "verified base did not skip redundant search");
    scans = 0;
    const full = calibrator.calibrateGrid({}, {ok:true,grid:{originX:30,originY:40,stepX:40,stepY:40}}, {
        acceptBaseGrid(){return false;},maxCandidates:2,coarseCandidates:2
    });
    assert(!full.earlyAccept && scans > 1, "independent geometry rejection was bypassed");
    console.log("PANEL START OK: deferred stages, cancel/stale callback, lock, failures, watch isolation, side, render cache");
} finally { global.setTimeout = realTimeout; global.clearTimeout = realClear; }
