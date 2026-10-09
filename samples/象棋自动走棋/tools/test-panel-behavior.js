// @engine quickjs
"use strict";
const assert = require("assert");
const windowModule = require("../src/ui/panel/window.js");
const compactModule = require("../src/ui/panel/compact.js");
function textView() { return { value:"", setText(t){this.value=t;} }; }
const logArea=textView(), logMeta=textView();
let reading=false, scrolls=0, lines=["first complete event"];
const state={win:{},modeKey:"suggest",UI_MODE_LABEL:{},MODE_LABEL:{},
    logWin:{logArea,logMeta,logTitle:textView(),logScroll:{attr(key,value){
        if(key==="canScrollDown")return String(reading);
        if(key==="scrollToEnd" && value){scrolls++;reading=false;}
    }}}};
const actions={panelTitleText(){return "title";},statusLine(){return "status";},stepLine(){return "step";},
    visibleLog(){return lines;},logTitle(){return "日志";},logMeta(){return "最近 "+lines.length+" 条";},
    renderParamSliders(){}};
const renderer=windowModule.create(state,actions);
renderer.render();
assert.strictEqual(scrolls,1);
renderer.render();
assert.strictEqual(scrolls,1,"heartbeat must not scroll");
reading=true;
lines.push("a very long new event: "+"full text ".repeat(200));
renderer.render();
assert.strictEqual(logArea.value,"first complete event","reading position must remain stable");
assert.strictEqual(logMeta.value,"回到最新 ↓");
assert.strictEqual(scrolls,1);
state.logFollowRequested=true;
renderer.render();
assert.strictEqual(logArea.value,lines.join("\n"),"follow must display complete events");
assert.strictEqual(scrolls,2);
lines.push("next");
renderer.render();
assert.strictEqual(logArea.value,lines.join("\n"));
assert.strictEqual(scrolls,3,"new event follows when already at bottom");

let stopped=0, started=0;
const handlers={};
const win={setTouchable(){},setDragHandle(){}};
for(const id of ["compactMode","compactStart","compactStop","compactExpand"])
    win[id]={on(event,fn){handlers[id]=fn;}};
global.floaty={window(){return win;}};
const compactState={MODES:["suggest","auto"],modeKey:"suggest",running:true,MODE_LABEL:{auto:"自动走棋"},
    bus:{syncConfig(){}}};
const compactActions={stopLoop(){stopped++;compactState.running=false;},startLoop(){started++;},
    logLine(){},render(){}};
compactModule.create(compactState,compactActions).ensureCompactWindow(0,0);
handlers.compactMode();
assert.strictEqual(stopped,1);
assert.strictEqual(started,0,"changing mode must wait for explicit start");
handlers.compactStart();
assert.strictEqual(started,1);
delete global.floaty;
console.log("PANEL BEHAVIOR OK: read/follow logs, full text, mode change stays stopped");
