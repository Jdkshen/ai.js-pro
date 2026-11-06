'use strict';
const assert = require('assert');
const path = require('path');
const root = path.resolve(__dirname, '../../apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋');
const placementModule = require(path.join(root, 'src/ui/panel/placement.js'));
const windowModule = require(path.join(root, 'src/ui/panel/window.js'));
const captureModule = require(path.join(root, 'src/app/panel/capture.js'));

function fixture(backend, panelY, logY) {
    const changes = [], waits = [];
    function window(id, y) {
        return { getX:()=>0, getY:()=>y,
            setContentVisibility:value=>{changes.push([id,value]);} };
    }
    const state = {adapter:{captureBackend:()=>backend,screenSize:()=>({width:1080,height:2400}),
        releaseFrame(){}},grid:{},geometryModule:{boundsOf:()=>({x:0,y:600,width:1080,height:1000})},
        win:window('panel',panelY),logWin:window('log',logY),logWindowEnabled:true,
        PANEL_W:1000,PANEL_H:200,LOG_H:200,PANEL:{marginX:0},hideDepth:0,
        panelMoveCounts:{hide:0,show:0},frameGateModule:{meanAbsDiff:()=>0},
        STABLE_GRAB_TRIES:2,STABLE_GRAB_GAP_MS:0,STABLE_GRAB_DIFF:6,
        SETTLED_CHANGE_BUDGET_MS:0,SETTLED_SETTLE_TRIES:2,SETTLED_CHANGE_GAP_MS:0};
    const actions = {logLine(){},boardRegionSamples:()=>[5],grabFrameRaw:()=>({image:{}})};
    Object.assign(actions, windowModule.create(state,actions));
    Object.assign(actions, placementModule.create(state,actions));
    Object.assign(actions, captureModule.create(state,actions));
    return {state,actions,changes,waits};
}

const oldSleep = global.sleep;
try {
    // Even calibration's explicit hide:true must not toggle isolated overlay windows.
    const isolated = fixture('window',700,800);
    global.sleep = ms=>isolated.waits.push(ms);
    isolated.actions.grabFrame();
    isolated.actions.grabStableFrame({hide:true});
    isolated.actions.grabSettledFrame({hide:true,changeBudgetMs:0});
    assert.deepStrictEqual(isolated.changes,[]);
    assert.deepStrictEqual(isolated.waits,[]);
    assert.strictEqual(isolated.state.hideDepth,0);

    // Plain capture above/below the board leaves both windows visible.
    const clear = fixture('screen',200,1800);
    global.sleep = ms=>clear.waits.push(ms);
    clear.actions.grabFrame(); clear.actions.grabStableFrame(); clear.actions.grabSettledFrame();
    assert.deepStrictEqual(clear.changes,[]);
    assert.deepStrictEqual(clear.waits,[]);

    // A log-only overlap must not blink the controls, including footer inspection.
    const log = fixture('screen',200,800);
    global.sleep = ms=>log.waits.push(ms);
    log.actions.grabFrame();
    assert.deepStrictEqual(log.changes,[['log',8],['log',0]]);
    assert.strictEqual(log.state.hideDepth,0);
    const footer = fixture('screen',200,2100);
    assert.strictEqual(footer.actions.needHideForCapture(),false);
    const footerLease = footer.actions.beginCaptureVisibility({includeFooter:true});
    assert.strictEqual(footerLease.hidden,true); footerLease.release(); footerLease.release();
    assert.deepStrictEqual(footer.changes,[['log',8],['log',0]]);

    // A panel-only overlap must leave a clear log visible and restore after failure.
    const panel = fixture('shell',700,1800);
    global.sleep = ms=>panel.waits.push(ms);
    panel.actions.grabFrameRaw=()=>{throw new Error('capture failed');};
    assert.throws(()=>panel.actions.grabFrame(),/capture failed/);
    assert.deepStrictEqual(panel.changes,[['panel',8],['panel',0]]);
    assert.strictEqual(panel.state.hideDepth,0);
    assert.deepStrictEqual(panel.waits,[90]);

    // Capture inside an existing click lease borrows it without extra hideDepth.
    const click = fixture('screen',700,800);
    global.sleep = ms=>click.waits.push(ms);
    click.actions.hidePanel('click'); click.state.clickLeaseActive=true;
    const before=click.changes.length;
    click.actions.grabFrame(); click.actions.grabStableFrame({hide:true});
    click.actions.grabSettledFrame({hide:true,changeBudgetMs:0});
    assert.strictEqual(click.changes.length,before);
    assert.strictEqual(click.state.hideDepth,1);
    assert.deepStrictEqual(click.waits,[]);
    click.state.clickLeaseActive=false; click.actions.showPanel();
    assert.strictEqual(click.state.hideDepth,0);
    assert.deepStrictEqual(click.changes,[['log',8],['panel',8],['log',0],['panel',0]]);

    // Drawing pixels clear even inside a click lease; the GL/control windows stay in place.
    const painted = fixture('screen',200,1800);
    let paintedContent = true;
    painted.actions.prepareOverlayForCapture=()=>{const cleared=paintedContent; paintedContent=false; return cleared;};
    painted.state.clickLeaseActive=true;
    global.sleep=ms=>painted.waits.push(ms);
    painted.actions.grabFrame(); painted.actions.grabStableFrame();
    assert.deepStrictEqual(painted.changes,[]);
    assert.deepStrictEqual(painted.waits,[90]);
    assert.strictEqual(painted.state.hideDepth,0);

    // A nested ordinary capture must not prematurely reveal the outer owner's windows.
    const nested = fixture('screen',700,800);
    const outer=nested.actions.beginCaptureVisibility();
    const inner=nested.actions.beginCaptureVisibility({hide:true});
    assert.strictEqual(inner.hidden,false); inner.release();
    assert.strictEqual(nested.state.hideDepth,1);
    assert.deepStrictEqual(nested.changes,[['panel',8],['log',8]]);
    outer.release(); assert.strictEqual(nested.state.hideDepth,0);
    assert.deepStrictEqual(nested.changes,[['panel',8],['log',8],['panel',0],['log',0]]);

    // An interruption during the compositor wait also restores exactly once.
    const interrupted = fixture('screen',700,1800);
    global.sleep=()=>{throw new Error('interrupted');};
    assert.throws(()=>interrupted.actions.grabStableFrame({hide:true}),/interrupted/);
    assert.strictEqual(interrupted.state.hideDepth,0);
    assert.deepStrictEqual(interrupted.changes,[['panel',8],['log',8],['panel',0],['log',0]]);
} finally { if (oldSleep === undefined) delete global.sleep; else global.sleep=oldSleep; }
console.log('CHESS_CAPTURE_VISIBILITY OK: isolated calibration, clear windows, selective hiding, footer, nesting, click lease, failure cleanup');
