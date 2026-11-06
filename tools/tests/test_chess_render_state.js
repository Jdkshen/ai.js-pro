'use strict';
const assert = require('assert');
const path = require('path');
const project = path.resolve(__dirname, '../../apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋');
const renderState = require(path.join(project, 'src/ui/render-state.js'));
const machineModule = require(path.join(project, 'src/automation/state-machine.js'));
const ticketModule = require(path.join(project, 'src/automation/move-ticket.js'));
const fenModule = require(path.join(project, 'src/chess/fen.js'));
const legalModule = require(path.join(project, 'src/chess/legal-moves.js'));
let now = 1000;
const layout = 'rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR';
const fen = layout + ' w - - 0 1';
const machine = machineModule.createMachine({allowClick:true}, {nowMono:() => now});
machine.dispatch({type:'START'});
machine.dispatch({type:'READY', modelVersion:'test-model'});
machine.dispatch({type:'GEOMETRY', geometryVersion:7, trusted:true});
machine.dispatch({type:'SNAPSHOT', snapshot:{fen, side:'w', stable:true, recognized:true, unknownCells:0}});
assert.strictEqual(machine.state(), 'THINKING', 'fixture must reach an actual search job');
const job = machine.currentJob();
machine.dispatch({type:'JOB_RESULT', jobId:job.jobId, ok:true, iccs:'b2b9', score:0, depth:18,
    source:'pikafish', engineMs:1234, pvIccs:['b2b9','a9b9'],
    expectedAfterFen:fenModule.buildFen(legalModule.applyMove(fenModule.parseFen(fen).board,{iccs:'b2b9'}).board,'b')});
const ticket = machine.currentTicket();
assert.ok(ticket, machine.reason());
assert.strictEqual(ticket.engineMs, 1234, 'search metadata must survive state-machine/ticket routing');
assert.deepStrictEqual(ticket.pvIccs, ['b2b9','a9b9']);
const input = {grid:{originX:50, originY:200, stepX:100, stepY:90, version:7},
    ticket, versions:machine.versions(), result:{ok:true, layout, unknownCells:0},
    state:machine.state(), running:true, nowMono:now,
    capture:{frameId:42, timestamp:99, windowId:12, packageName:'game.test', captureMs:20},
    timing:{smart:{total:140, sections:[{name:'抓帧（含藏窗）',ms:35},{name:'稳定帧',ms:0},{name:'字形识别',ms:105}]}}};
const rendered = renderState.build(input);
assert.strictEqual(rendered.hasMove, true);
assert.deepStrictEqual(rendered.from, {x:150, y:830});
assert.deepStrictEqual(rendered.to, {x:150, y:200});
assert.deepStrictEqual(rendered.fromSquare, {col:1,row:7});
assert.deepStrictEqual(rendered.toSquare, {col:1,row:0});
assert.strictEqual(rendered.items.length, 2);
assert.ok(rendered.items.every(item => item.kind === 'arrow'), 'only direction arrows, no boxes/circles/HUD');
assert.strictEqual(rendered.opponentMove, 'a9b9');
assert.strictEqual(rendered.items[1].color, '#40C4FF');
assert.match(rendered.text, /起点 第2列8行 → 目标 第2列1行/);
const turned = renderState.build({...input,grid:{...input.grid,orientation:'RED_TOP'}});
assert.deepStrictEqual(turned.from, {x:750,y:380}, 'black-bottom display must use the same rotation as clicks');
assert.deepStrictEqual(turned.to, {x:750,y:1010});
assert.deepStrictEqual(turned.fromSquare, {col:7,row:2});
assert.deepStrictEqual(turned.toSquare, {col:7,row:9});
assert.strictEqual(rendered.items[0].kind, 'arrow');
assert.strictEqual(rendered.items[0].y2, 200, 'screen coordinate target must point toward the actual destination');
assert.strictEqual(rendered.frameId, 42);
assert.match(rendered.text, /评分 0cp/);
assert.match(rendered.text, /帧检查 0ms/);
assert.match(rendered.text, /截图 20ms/);
assert.match(rendered.text, /抓帧 35ms/);
assert.match(rendered.text, /算棋 1234ms/);
assert.match(rendered.text, /PV b2b9 a9b9/);
const cells = fenModule.parseFen(fen).board;
assert.strictEqual(renderState.build({...input, result:{ok:true, chars:cells, unknownCells:0}}).hasMove, true,
    'glyph results without layout must render the current valid move');
const changedCells = cells.slice(); changedCells[0] = null;
assert.strictEqual(renderState.build({...input, result:{...input.result, chars:changedCells}}).hasMove, false,
    'changed/repaired cells must override a stale layout string');
for (const patch of [
    {running:false}, {state:'SUGGESTED'}, {state:'THINKING'}, {state:'RECOVERING'}, {state:'PAUSED'}, {captureInvalid:true},
    {result:{ok:false,layout,unknownCells:0}}, {result:{ok:true,layout,unknownCells:1}},
    {result:{ok:true,layout:'changed',unknownCells:0}}, {nowMono:ticket.expiresAtMono},
    {versions:{...input.versions, sessionId:999}}, {versions:{...input.versions, positionVersion:999}},
    {versions:{...input.versions, modelVersion:'changed-model'}},
    {grid:{...input.grid, version:8}}, {ticket:{...ticket, executionState:'CANCELLED'}}
]) {
    const stale = renderState.build({...input, ...patch});
    assert.strictEqual(stale.hasMove, false, JSON.stringify(patch));
    assert.strictEqual(stale.items.length, 0, 'invalid/stale move must leave no arrow or endpoint');
    assert.strictEqual(stale.engine, null, 'stale PV/score must be cleared with the arrow');
}
const invalidPv = renderState.build({...input, ticket:{...ticket,pvIccs:['a0a1','b2b9']}});
assert.deepStrictEqual(invalidPv.engine.pvIccs, ['b2b9'], 'PV must begin with the displayed move');
const polluted = ticketModule.create({...ticket, nowMono:now, pvIccs:['b2b9', {}, 'invalid'], engineMs:NaN});
assert.deepStrictEqual(polluted.pvIccs, ['b2b9']);
assert.strictEqual(polluted.engineMs, null);
const hud = renderState.hudBounds({width:1080,height:2400}, {top:100,bottom:2200},
    [{y:100,height:300}, {y:600,height:1300}, {y:2000,height:200}]);
assert.ok(hud.top > 400 && hud.bottom < 600, 'HUD must avoid the panel, board and log window');
assert.strictEqual(renderState.hudBounds({width:1080,height:2400}, {top:100,bottom:2200},
    [{y:100,height:2100}]), null, 'no free band must disable the HUD instead of covering touch controls');

// Exercise the actual panel renderer: new search result, idle poll, failure, resume.
const packets = [];
const panel = {grid:input.grid, drawMode:'cells', modeKey:'auto', running:true, overlayOn:false,
    STATE_LABEL:{SUGGESTED:'建议'}, progress:{}, overlayDraw:{build:() => ({items:[],text:''})},
    driver:{machine}, boardService:{lastTiming:() => input.timing},
    adapter:{captureBackend:() => 'window', windowCaptureNeedsCalibration:() => false,
        overlayShow:() => true, overlayUpdate:(items,text) => { packets.push({items,text}); return true; }}};
const realNow = Date.now;
Date.now = () => now;
try {
    let selected='shell', serviceClears=0;
    const selectionCalls=[];
    const selecting={modeKey:'auto',drawMode:'moves',running:false,grid:input.grid,
        boardService:{clear(){serviceClears++;}},
        adapter:{captureBackend:()=>selected,capabilities:()=>({captureWindow:true}),
            setCaptureBackend:(key,options)=>{selected=key;selectionCalls.push({key,options});}}};
    const captureSelector=require(path.join(project,'src/ui/panel/overlay.js')).create(selecting,{logLine(){}});
    assert.strictEqual(captureSelector.prepareContinuousMoveCapture(),true);
    assert.deepStrictEqual(selectionCalls,[{key:'window',options:{persist:false}}]);
    assert.strictEqual(serviceClears,1); assert.strictEqual(selecting.grid,null);
    assert.strictEqual(captureSelector.prepareContinuousMoveCapture(),false,'isolation is selected once before calibration');
    selected='shell'; selecting.running=true;
    assert.strictEqual(captureSelector.prepareContinuousMoveCapture(),false,'live coordinates cannot silently switch source');
    const renderer = require(path.join(project, 'src/ui/panel/overlay.js')).create(panel, {logLine:() => {}});
    const view = {...input.result, frame:{captureMetadata:input.capture}};
    renderer.refreshOverlay(view);
    assert.strictEqual(packets[0].items[0].kind, 'arrow');
    renderer.refreshOverlay(view);
    assert.strictEqual(packets.length, 1, 'unchanged search polling must not redraw a full packet');
    panel.renderCaptureFailed = true;
    renderer.refreshOverlay(view);
    assert.strictEqual(packets.at(-1).items.length, 0, 'failed capture must clear the previously rendered move');
    panel.renderCaptureFailed = false;
    renderer.refreshOverlay(view);
    assert.strictEqual(packets.at(-1).items[0].kind, 'arrow', 'same state can be rendered again after clearing');
    for (const backend of ['auto','screen','shell','window']) {
        const livePackets = [];
        const livePanel = {...panel, drawMode:'moves', overlayOn:false,
            overlayDraw:{build:() => {throw new Error('move-only display must omit recognition grid');}},
            adapter:{...panel.adapter, captureBackend:() => backend,
                overlayUpdate:(items,text) => {livePackets.push({items,text}); return true;}}};
        const liveRenderer = require(path.join(project, 'src/ui/panel/overlay.js')).create(livePanel,{logLine(){}});
        liveRenderer.refreshOverlay(view);
        if (backend === 'window') {
            assert.strictEqual(livePackets[0].items[0].kind, 'arrow');
            assert.strictEqual(livePackets[0].text, '', 'automatic arrows omit diagnostic HUD');
            assert.strictEqual(liveRenderer.prepareOverlayForCapture(), false);
            assert.strictEqual(livePackets.length, 1, 'isolated capture keeps move visible');
        } else {
            assert.strictEqual(livePackets.length, 0, 'ordinary capture must not blink live automatic arrows');
            livePanel.modeKey='watch'; livePanel.drawMode='cells'; livePanel.overlayDraw={build:() => ({items:[{kind:'rect',x1:1,y1:1,x2:2,y2:2}],text:''})};
            liveRenderer.refreshOverlay({...view, unknownCells:1});
            assert.strictEqual(liveRenderer.prepareOverlayForCapture(), true);
            assert.deepStrictEqual(livePackets.at(-1), {items:[],text:''}, 'ordinary capture must contain no drawing pixels');
            assert.strictEqual(liveRenderer.prepareOverlayForCapture(), false, 'one clear per acquisition');
            liveRenderer.refreshOverlay({...view, unknownCells:1});
            assert.strictEqual(livePackets.at(-1).items[0].kind, 'rect');
            livePanel.adapter.overlayUpdate = () => false;
            assert.throws(() => liveRenderer.prepareOverlayForCapture(), /本次截图取消/);
        }
    }
    const post = {ok:true,layout:ticket.expectedAfterFen,unknownCells:0};
    const wait = {...input,ticket:null,retainedTicket:ticket,result:post,state:'WAIT_TURN',
        versions:{...input.versions,positionVersion:ticket.basePositionVersion+3},nowMono:ticket.expiresAtMono+1000};
    assert.strictEqual(renderState.build(wait).items.length,2,'confirmed own arrow and opponent prediction persist while waiting');
    assert.strictEqual(renderState.build({...wait,state:'WAIT_STABLE'}).items.length,2,
        'real confirmation resets stable observation and must retain the same arrow');
    assert.strictEqual(renderState.build({...wait,result:{...post,layout:'changed'}}).items.length,0);
    assert.strictEqual(renderState.build({...wait,versions:{...wait.versions,positionVersion:999}}).items.length,0);
    assert.strictEqual(renderState.build({...input,ticket:{...ticket,pvIccs:['b2b9','b9b8']}}).items.length,1,
        'illegal/wrong-side opponent prediction must never be drawn');
    // The real machine increments the position version during verification,
    // confirmation and the first stable post-move frame. All must keep the arrows.
    machine.dispatch({type:'CLICK_RESULT',ticket,status:'SENT',clickCount:2});
    machine.dispatch({type:'SNAPSHOT',snapshot:{fen:ticket.expectedAfterFen,side:'b',stable:true,recognized:true,unknownCells:0}});
    machine.dispatch({type:'VERIFY_RESULT',ticket,verdict:{result:'CONFIRMED',reason:'test'}});
    assert.strictEqual(machine.state(),'WAIT_STABLE');
    renderer.refreshOverlay(post);
    assert.strictEqual(packets.at(-1).items.length,2,'real confirmation must not clear arrows');
    const beforeStable=packets.length;
    machine.dispatch({type:'SNAPSHOT',snapshot:{fen:ticket.expectedAfterFen,side:'b',stable:true,recognized:true,unknownCells:0}});
    assert.strictEqual(machine.state(),'WAIT_TURN');
    renderer.refreshOverlay(post);
    assert.strictEqual(packets.length,beforeStable,'waiting for the opponent must not resend/clear unchanged arrows');
    machine.dispatch({type:'PAUSE'});
    renderer.refreshOverlay(view);
    assert.strictEqual(packets.at(-1).items.length, 0, 'pause must clear a valid earlier suggestion');
    renderer.clearOverlay();
    assert.strictEqual(packets.at(-1).text, '');
} finally { Date.now = realNow; }
console.log('CHESS_RENDER_STATE OK: live search metadata, screen arrows, stale-result rejection, panel clearing, idle dedup');
