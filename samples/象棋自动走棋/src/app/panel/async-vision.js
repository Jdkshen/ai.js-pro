// @engine quickjs
"use strict";
const workerModule = require("../../runtime/vision-worker.js");
function create(state,actions) {
    function observeAsync(request,config) {
        if (state.visionModeKey === "inline" || state.visionWorkerFailed ||
            state.adapter.captureBackend() !== "window" || !state.grid || !workerModule.available()) return null;
        const window = state.adapter.windowCaptureInfo();
        if (!window) return null; // Calibration must bind the target before off-thread capture.
        if (!state.visionWorker) {
            const id=typeof engines !== "undefined" ? engines.myEngine().id : Date.now();
            state.visionWorker=workerModule.create({dir:state.BASE_DIR,
                prefix:state.FRAME_DIR+"/vision-"+id+"-"+Date.now()});
            actions.logLine("后台视觉已启用：1 个常驻 worker，截图与识别串行；结果回面板核对");
        }
        const job={grid:state.grid,window:window,options:request,config:config,stamp:request.stamp,
            calibrationEpoch:state.visionCalibrationEpoch || 0,
            awaitingOpponent:!!(state.driver && state.driver.machine.awaitingOpponentMove())};
        const key=JSON.stringify([job.grid,job.calibrationEpoch,request,config,job.awaitingOpponent,
            window.windowId,window.packageName,window.bounds,window.rotation]);
        try {
            const packet=state.visionWorker.step(key,job);
            state.observing=!!packet.pending;
            if (packet.pending) return packet;
            if (!packet.result) return packet;
            (packet.logs || []).forEach(actions.logLine);
            const result=packet.result;
            result.asyncMs=packet.ms;result.mergeStats=packet.mergeStats;
            state.tickAsyncObsMs=packet.ms;
            actions.logLine("后台观察："+packet.ms+"ms｜常驻 worker "+state.visionWorker.stats().workerStarts+" 个");
            if (result.gameOver) state.gameOverSeen=true;
            if (result.windowCaptureInvalid && typeof state.adapter.invalidateWindowCaptureGeometry === "function")
                state.adapter.invalidateWindowCaptureGeometry();
            if (result.ok) {
                if (typeof actions.stageMark === "function") actions.stageMark("vision:render:begin");
                actions.refreshOverlay(result,null);
                if (typeof actions.stageMark === "function") actions.stageMark("vision:render:end");
                actions.logPipelineNote(result);
            }
            return result;
        } catch (error) {
            state.visionWorkerFailed=true;state.observing=false;
            state.visionWorker.close();
            actions.logLine("后台视觉不可用，恢复同步观察："+error);
            return null;
        }
    }
    function cancelVision() {
        state.observing=false;
        // A successful calibration may retain identical coordinates and window ID.
        // Its new epoch must still clear the worker's sticky invalid-window latch.
        state.visionCalibrationEpoch=(state.visionCalibrationEpoch || 0)+1;
        if (state.visionWorker) state.visionWorker.cancel();
    }
    function closeVision() {
        state.observing=false;
        if (state.visionWorker) state.visionWorker.close();
    }
    return {observeAsync:observeAsync,cancelVision:cancelVision,closeVision:closeVision};
}
module.exports={create:create};
