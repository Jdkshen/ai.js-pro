'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const root = path.resolve(__dirname, '../..');
const source = fs.readFileSync(path.join(root, 'modules/engine/src/main/cpp/quickjs_jni.cpp'), 'utf8');
const start = source.indexOf('const drawingAccum ='), end = source.indexOf('// ---- app module ----', start);
let wire = [];
const context = {global:{}, __aiNativeImGuiPanel:(op, json) => { wire.push({op, data:JSON.parse(json)}); return '{"ok":true,"events":[]}'; }};
vm.runInNewContext(source.slice(start, end), context);
const api = context.global.drawing.panel;
api.show({buttons:[{id:'stop',label:'暂停'}]}); api.update({visible:false}); api.poll(); api.hide();
assert.deepStrictEqual(wire.map(x=>x.op), ['show','update','poll','close']);
assert.strictEqual(wire[0].data.buttons[0].label,'暂停');
const project = path.join(root, 'apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋');
const create = require(path.join(project,'src/ui/imgui-compact-window.js')).createWindow;
const compactModule = require(path.join(project,'src/ui/panel/compact.js'));
const windowModule = require(path.join(project,'src/ui/panel/window.js'));
const placementModule = require(path.join(project,'src/ui/panel/placement.js'));
const busModule = require(path.join(project,'src/app/panel/bus.js'));
let queue=[], calls=[], updates=0, hidden=0, intervalCallback;
global.drawing={panel:{show(){return {ok:true,x:20,y:250,width:888,height:288};},
    poll(){const events=queue;queue=[];return {ok:true,x:20,y:250,width:888,height:288,events};},
    update(){updates++;},hide(){hidden++;}}};
const oldInterval=global.setInterval,oldClear=global.clearInterval;
global.setInterval=fn=>{intervalCallback=fn;return 1;};global.clearInterval=()=>{};
const state={COMPACT_W:888,collapsed:true,running:false,MODES:['watch','advice','auto'],modeKey:'watch',MODE_LABEL:{advice:'建议'},
    bus:{readOther(){return {fresh:true,state:{running:true}};},stopOther(){calls.push('stopOther');},syncConfig(){}}};
const actions={compactStateText(){return state.running?'运行中':'未运行';},logLine(){},render(){},
    runPanelCommand(cmd){calls.push(cmd);state.running=cmd==='START';},setPanelCollapsed(value){state.collapsed=value;}};
try {
    const win=create(state,actions,20,250);
    const event=id=>({id,at:Date.now()});
    queue=[event('toggle')]; intervalCallback();assert.deepStrictEqual(calls,['START']);
    queue=[event('recognize')];win.poll();assert.strictEqual(calls.length,1,'recognition is disabled while running');
    queue=[event('toggle'),event('stop'),event('toggle')];win.poll();assert.deepStrictEqual(calls,['START','STOP','stopOther']);
    queue=[{id:'toggle',at:Date.now()-3000}];win.poll();assert.strictEqual(calls.length,3,'stale start cannot run');
    queue=[event('locate'),event('recognize')];win.poll();assert.deepStrictEqual(calls.slice(-2),['RELOCATE','RECOGNIZE']);
    win.updateState();const previous=updates;win.updateState();assert.strictEqual(updates,previous,'unchanged status does not redraw');
    queue=[event('drag')];win.poll();assert.strictEqual(state.panelUserMoved,true);
    queue=[event('more')];win.poll();assert.strictEqual(state.collapsed,false);
    queue=[event('toggle')];win.poll();assert.strictEqual(calls.at(-1),'RECOGNIZE','hidden panel ignores input');
    win.close();win.close();assert.strictEqual(hidden,1);

    // Native controls must be available with ordinary screen/shell capture too.
    for (const backend of ['auto','screen','shell','window']) {
        const nativeState={...state,collapsed:true,compactWin:null,COMPACT_W:888,COMPACT_H:88,hideDepth:0,
            panelMoveCounts:{hide:0,show:0},adapter:{captureBackend:()=>backend}};
        const native=compactModule.create(nativeState,actions).ensureCompactWindow(20,250);
        assert.strictEqual(native.isImGui,true,backend+' should use native controls');
        assert.strictEqual(nativeState.COMPACT_H,288,'geometry uses actual native height');
        const visibility=windowModule.create(nativeState,{});
        assert.strictEqual(visibility.setActivePanelVisibility(false,'capture'),true);
        assert.strictEqual(visibility.setActivePanelVisibility(true),true);

        nativeState.grid={};
        nativeState.geometryModule={boundsOf:()=>({x:0,y:300,width:1080,height:1000})};
        const placement=placementModule.create(nativeState,{activePanelWindow:()=>native});
        assert.strictEqual(placement.needHideForCapture(),backend!=='window',
            'ordinary capture hides an overlapping native panel, isolated capture does not');

        let selected=backend,stopped=0,published=0;
        nativeState.running=true;
        nativeState.adapter.setCaptureBackend=value=>{selected=value;};
        const bus=busModule.create(nativeState,{stopLoop(){stopped++;nativeState.running=false;},
            publishGrid(value){assert.strictEqual(value,null);published++;},render(){}});
        bus.applyConfigFromPage('capture',backend==='window'?'screen':'window');
        assert.notStrictEqual(selected,backend);
        assert.strictEqual(stopped,1);assert.strictEqual(published,1);
        assert.strictEqual(nativeState.compactWin,native,'capture switch preserves the controls');
        assert.strictEqual(nativeState.collapsed,true,'capture switch preserves collapsed state');
        native.close();
    }
    const panelApi=global.drawing.panel;
    global.drawing.panel={show:panelApi.show};
    assert.strictEqual(create(state,actions,0,0),null,'incomplete host API must use the compatibility UI');
    global.drawing.panel=panelApi;
} finally {global.setInterval=oldInterval;global.clearInterval=oldClear;delete global.drawing;}
console.log('IMGUI_PANEL OK: bridge, commands, stale input, lifecycle, all capture modes, capture hiding and source switch');
