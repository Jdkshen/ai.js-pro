// @engine quickjs
"use strict";

const factory = require("../vision/pipeline-factory.js");
const locator = require("../vision/locate-board.js");
const sameGrid = require("../vision/sift-board-locator.js").sameGrid;
const frameGate = require("../vision/frame-gate.js");
const serviceModule = require("../services/board-service.js");
const recognition = require("../automation/driver/recognition.js");

function windowKey(info) {
    if (!info || !info.bounds) return "";
    const b = info.bounds;
    return [info.windowId,info.packageName,b.left,b.top,b.right,b.bottom,info.rotation].join(",");
}
function serve(args) {
    const adapter = require("./aijs-adapter.js");
    const samples = factory.createSamples(adapter), logs = [];
    function note(text) { if (logs.length < 30) logs.push(String(text)); }
    const state = {adapter:adapter, BASE_DIR:args.dir, running:true, recentFrame:null,
        frameGateModule:frameGate, STABLE_GRAB_TRIES:4, STABLE_GRAB_GAP_MS:130, STABLE_GRAB_DIFF:6,
        SETTLED_CHANGE_BUDGET_MS:800, SETTLED_CHANGE_GAP_MS:120, SETTLED_SETTLE_TRIES:4,
        gameOverProbeTick:0, GAME_OVER_PROBE_EVERY:5, GAME_OVER_WORDS:["再来一局","复盘分析"]};
    const ocr = factory.createOcrHolder({log:note});
    const actions = {logLine:note, boardRegionSamples:samples, ocrRuntime:ocr.get,
        needHideForCapture:function () {return false;},
        beginCaptureVisibility:function () {return {hidden:false,release:function () {}};}};
    Object.assign(actions, require("../app/panel/capture.js").create(state,actions));
    const probe = require("../app/panel/grid-check.js").create(state,actions).probeGameOver;
    let current = null, geometryKey = "", primed = false, canceled = false, lastControl = null;
    function readControl() {
        try {lastControl=JSON.parse(files.read(args.controlFile));} catch (ignore) { /* tolerate a partial heartbeat write */ }
        return lastControl;
    }
    function alive() {
        const c = readControl();
        return !!c && !c.stop && Date.now() - c.aliveAt < 10000 &&
            !(current && c.canceledThrough >= current.id);
    }
    actions.grabFrameRaw = function () {
        if (!alive()) {canceled=true;state.running=false;return null;}
        const image = adapter.capture("full",2000,{backend:"window",
            packageName:current.request.window.packageName,windowId:current.request.window.windowId});
        const info = adapter.windowCaptureInfo();
        if (windowKey(info) !== windowKey(current.request.window)) {
            adapter.releaseFrame(image);
            throw new Error("目标窗口位置或方向已改变，后台帧作废");
        }
        return {image:image,channel:adapter.captureChannel(),at:Date.now(),window:info};
    };
    adapter.setCaptureBackend("window",{persist:false});
    locator.configureSift({baseDir:args.dir});
    const glyph = factory.createGlyphSource({adapter:adapter,baseDir:args.dir,
        confirmGeometry:locator.confirmGeometry,log:note});
    const service = serviceModule.createBoardService({
        ports:{now:Date.now,capture:function () {
            return current.request.awaitingOpponent
                ? actions.grabSettledFrame({allowAbort:true}) : actions.grabStableFrame();
        }},
        checkGeometry:function (image,grid) {
            if (!primed) {
                const located = locator.locateBoardInImage(image,{orientation:grid.orientation});
                if (!located || !located.ok) return {ok:false,geometryUncertain:true,reason:"后台几何首帧复查失败"};
                if (!sameGrid(grid,located.grid)) return {ok:false,geometryChanged:true,reason:"后台复查棋盘坐标变化"};
                primed = true;
            }
            return locator.checkGeometry(image,grid);
        },
        vision:{gate:frameGate.create({log:note}),samples:samples,log:note},glyph:glyph,note:note
    });
    let lastId = 0;
    try {
        for (;;) {
            const c = readControl();
            if (!c || c.stop || Date.now()-c.aliveAt >= 10000) break;
            let packet = null;
            try {packet=JSON.parse(files.read(args.requestFile));} catch (ignore) { /* writer still busy */ }
            if (!packet || packet.id <= lastId) {sleep(40);continue;}
            lastId = packet.id; current = packet; canceled=false; logs.length=0;state.running=true;
            const started = Date.now(), request = packet.request;
            let result, capturedAt = 0, mergeStats = {mergedFrames:0,frameConflicts:0};
            try {
                const key=JSON.stringify([request.grid,windowKey(request.window),request.stamp.sessionId,
                    request.calibrationEpoch || 0]);
                if (key !== geometryKey) {
                    geometryKey=key;primed=false;service.resetFrameBaseline();
                    adapter.setCaptureBackend("window",{persist:false});
                    state.lastSettledSamples=null;
                    if (state.gameOverDetector) state.gameOverDetector.close();
                    state.gameOverDetector=null;state.gameOverSeen=false;
                }
                state.grid=request.grid;service.setGrid(request.grid);
                const merged=recognition.create({cfg:request.config,stats:mergeStats,clock:sleep,
                    opts:{recognize:function (options) {
                        const one=service.recognizeSmart(options);
                        if (probe(one && one.frame && one.frame.image,false))
                            return {ok:false,gameOver:true,reason:"对局已结束"};
                        return one;
                    }}},{note:note});
                result=merged.recognizeMerged(request.options);
                if (canceled || !alive()) result={ok:false,canceled:true,reason:"视觉任务已取消"};
                // Exclude every image/Java handle explicitly. A plain frame has only metadata.
                if (result) {
                    const clean={};
                    ["ok","reason","gameOver","canceled","unstable","geometryChanged","geometryUncertain",
                        "royalUnsafe","royal","method","grid","chars","rawChars","unknownCells","layout","validation","glyphStats","centers"]
                        .forEach(function (key) {if(result[key] !== undefined) clean[key]=result[key];});
                    clean.frame={channel:adapter.captureChannel(),window:adapter.windowCaptureInfo()};result=clean;
                    result.timing=service.lastTiming();
                }
            } catch (error) {
                const windowInvalid=/窗口位置|方向已改变|找不到目标应用窗口|旧坐标已停用/.test(String(error));
                result={ok:false,reason:"后台观察异常："+error,
                    geometryChanged:windowInvalid,windowCaptureInvalid:windowInvalid};
            } finally {
                capturedAt = state.recentFrame ? state.recentFrame.at : 0;
                actions.clearRecentFrame();
            }
            files.write(args.resultFile,JSON.stringify({id:packet.id,key:packet.key,result:result,
                logs:logs,mergeStats:mergeStats,ms:Date.now()-started,capturedAt:capturedAt,finishedAt:Date.now()}));
            current=null;
        }
    } finally {
        actions.clearRecentFrame();glyph.release();ocr.release();locator.releaseSift();
        if (state.gameOverDetector) state.gameOverDetector.close();
        // Best effort cleanup after the owner has closed or its heartbeat expired.
        [args.requestFile,args.resultFile,args.controlFile].forEach(function (file) {
            try {files.remove(file);} catch (ignore) { /* local diagnostic files are harmless */ }
        });
        console.log("[vision-worker] 协作退出" +
            (typeof __aiNativeFrameStats === "function" ? "｜原生帧 " + JSON.stringify(__aiNativeFrameStats()) : ""));
    }
    return "vision worker stopped";
}
module.exports = {serve:serve,windowKey:windowKey};
