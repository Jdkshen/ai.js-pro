// @engine quickjs
"use strict";

/** One reusable QuickJS worker. Only JSON crosses engines; images stay in the worker. */
function workerTask() {
    return require(__args.dir + "/src/runtime/vision-task.js").serve(__args);
}
function available() {
    return typeof threads !== "undefined" && typeof threads.start === "function" &&
        typeof files !== "undefined" && typeof files.write === "function";
}
function create(options) {
    const o = options || {}, now = o.now || Date.now;
    const io = o.files || files, spawn = o.spawn || function (args) { return threads.start(workerTask, args); };
    const prefix = o.prefix, requestFile = prefix + ".request.json", resultFile = prefix + ".result.json";
    const controlFile = prefix + ".control.json";
    let thread = null, pending = null, serial = 0, closed = false, lastAlive = 0, canceledThrough = 0;
    const stats = { submitted:0, completed:0, stale:0, workerStarts:0, lastMs:0 };
    function finished() {
        const life = thread && thread.getResult();
        return life && (life.status === "error" || life.status === "success") ? life : null;
    }
    function cleanup() {
        if (typeof io.remove !== "function") return;
        [requestFile,resultFile,controlFile].forEach(function (path) {
            try {io.remove(path);} catch (ignore) { /* best effort */ }
        });
    }
    function control(stop) {
        lastAlive = now();
        io.write(controlFile, JSON.stringify({aliveAt:lastAlive, stop:!!stop, canceledThrough:canceledThrough}));
    }
    function cancel() {
        const id = pending && pending.id;
        if (pending) stats.stale++;
        canceledThrough = Math.max(canceledThrough, id || 0);
        pending = null;
        if (thread && !closed) control(false);
    }
    function ensure() {
        if (thread || closed) return !closed;
        control(false);
        thread = spawn({dir:o.dir, requestFile:requestFile, resultFile:resultFile, controlFile:controlFile});
        if (!thread) throw new Error("视觉 worker 未创建");
        stats.workerStarts++;
        return true;
    }
    function step(key, request) {
        if (closed) return {ok:false, reason:"视觉 worker 已关闭"};
        ensure();
        const life = finished();
        if (life) {
            pending = null; closed = true; cleanup();
            throw new Error(life.error || "视觉 worker 已退出");
        }
        keepAlive();
        if (pending && pending.key !== key) cancel();
        if (!pending) {
            pending = {id:++serial, key:key, at:now()};
            io.write(requestFile, JSON.stringify({id:pending.id, key:key, request:request}));
            stats.submitted++;
        }
        let packet = null;
        try { packet = JSON.parse(io.read(resultFile)); } catch (ignore) { /* no complete result yet */ }
        if (packet && packet.id === pending.id && packet.key === pending.key) {
            pending = null;
            const capturedAt = packet.capturedAt || packet.finishedAt;
            if (now() - capturedAt > 1800 || capturedAt > now()) {
                stats.stale++;
                return {pending:true, reason:"视觉结果已过期，重新采集"};
            }
            stats.completed++; stats.lastMs = packet.ms;
            return packet;
        }
        const elapsed = now() - pending.at;
        if (elapsed > 12000) {
            cancel();
            return {ok:false, reason:"后台观察超时（12s），本帧不采纳"};
        }
        return {pending:true, elapsedMs:elapsed, reason:"后台截图/识别中"};
    }
    function close() {
        if (closed) return;
        if (finished()) {pending=null;closed=true;cleanup();return;}
        cancel(); control(true); closed = true;
        // Never interrupt JNI while it owns images. The worker exits cooperatively.
    }
    function keepAlive() {
        if (thread && !closed && now() - lastAlive >= 1500 && !finished()) control(false);
    }
    return {step:step, cancel:cancel, close:close, keepAlive:keepAlive, busy:function () {return !!pending;},
        stats:function () {return Object.assign({},stats);}};
}
module.exports = {available:available, create:create, workerTask:workerTask};
