// @engine quickjs
"use strict";

// Inspect current image readings before any historical or rule based repair.
function inspect(board) {
    if (!Array.isArray(board) || board.length !== 90) return null;
    const red = board.filter(function (ch) {return ch === "K";}).length;
    const black = board.filter(function (ch) {return ch === "k";}).length;
    const unsafe = red !== 1 || black !== 1;
    return {unsafe:unsafe, red:red, black:black,
        reason:unsafe ? "将帅读数异常（红帅 " + red + "、黑将 " + black + "），禁止补回并落子" : ""};
}

function protect(result) {
    if (!result) return result;
    const royal = inspect(result.rawChars || result.chars);
    if (!royal || !royal.unsafe) return result;
    return Object.assign({}, result, {ok:false, royalUnsafe:true, royal:royal, reason:royal.reason});
}

function freshSide(result, index) {
    const evidence = result && result.glyphStats && result.glyphStats.circleEvidence;
    return evidence && evidence.active && evidence.sides ? evidence.sides[index] || null : null;
}

function frameId(result) {
    const frame = result && result.frame && (result.frame.window || result.frame);
    return frame && frame.frameId != null ? frame.frameId : null;
}

/** A transient miss must be checked against fresh pixels before historical repair. */
function recheckRoyal(result, request, read, wait) {
    const royal = result && inspect(result.rawChars || result.chars);
    if (!royal || !royal.unsafe || result.pending || result.geometryChanged || result.geometryUncertain ||
            (request && request.independentRead)) return {result:result, attempted:false};
    const freshRequest = Object.assign({}, request, {freshRead:true, independentRead:true,
        referenceBoard:null, frames:2, asyncObservation:false});
    let fresh;
    try {
        wait();
        fresh = read(freshRequest);
    } catch (error) {
        return {result:result, attempted:true, recovered:false, reason:String(error && error.message || error)};
    }
    const latest = fresh && inspect(fresh.rawChars || fresh.chars);
    const previousId = frameId(result), nextId = frameId(fresh);
    const sameFrame = previousId != null && nextId === previousId;
    if (fresh && fresh.ok && !fresh.pending && !fresh.geometryChanged && !fresh.geometryUncertain &&
            latest && !latest.unsafe && !sameFrame) {
        return {result:fresh, attempted:true, recovered:true};
    }
    // A failed recheck never permits a move or fills a king from the old position.
    return {result:sameFrame || !latest || fresh.geometryChanged || fresh.geometryUncertain ? result : fresh,
        attempted:true, recovered:false, reason:sameFrame ? "复核仍是同一缓存帧" : fresh && fresh.reason || "新帧仍未看清将帅"};
}

function createGuard() {
    let session = null, lastFrame = null, key = "", streak = 0;
    return {check:function (result, sessionId, observationId) {
        if (session !== sessionId) {session=sessionId;lastFrame=null;key="";streak=0;}
        const royal = result && (result.royal || inspect(result.rawChars || result.chars));
        if (!royal) {streak=0;key="";return null;}
        if (!royal.unsafe) {streak=0;key="";return null;}
        const frame = result.frame && (result.frame.window || result.frame);
        const id = frame && frame.frameId != null ? "frame:" + frame.frameId : "observation:" + observationId;
        const nextKey = royal.red + ":" + royal.black;
        if (id !== lastFrame) {streak = key === nextKey ? streak + 1 : 1;lastFrame=id;key=nextKey;}
        return {unsafe:true, confirmed:streak >= 2, streak:streak, reason:royal.reason};
    }};
}

module.exports = {inspect:inspect, protect:protect, freshSide:freshSide, createGuard:createGuard, recheckRoyal:recheckRoyal};
