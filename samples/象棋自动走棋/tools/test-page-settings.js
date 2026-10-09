// @engine quickjs
"use strict";
const assert = require("assert");
const settings = require("../src/app/session-settings.js");
const data = {};
const storage = { get(k,d){return k in data ? data[k] : d;}, put(k,v){data[k]=v;} };
global.storages = {create(){return storage;}};
assert.deepStrictEqual(settings.read(), settings.DEFAULTS);
assert.strictEqual(settings.normalize({threads:99,thinkMs:1,searchDepth:NaN}).threads,8);
assert.strictEqual(settings.normalize({thinkMs:1}).thinkMs,500);
Object.assign(data,{searchMode:"depth",searchDepth:24,depthTimeoutMs:3000,thinkMs:500,competitiveSearchV1:false});
assert.strictEqual(settings.read().searchMode,"time"); assert.strictEqual(settings.read().thinkMs,5000);
settings.write({searchMode:"depth",searchDepth:24,thinkMs:500});
assert.strictEqual(settings.read().searchMode,"depth", "explicit user choice was migrated again");
settings.write(settings.DEFAULTS);

const calls = {};
const driver = {};
["Movetime","SearchDepth","DepthTimeout","BookOwnMoves","BookRandom","WinSeek","Pikafish"].forEach(k=>driver["set"+k]=v=>{calls[k]=v;});
const state = {modeKey:"watch",mySideKey:"w",clickMethodKey:"auto",engineModule:{configurePikafish(v){calls.engine=v;}}};
const actions = {setPanelSide(v){state.mySideKey=v;},render(){},renderParamSliders(){},stopLoop(){calls.stop=true;},logLine(){}};
const panel = require("../src/app/panel/settings.js").create(state,actions);
panel.refreshSharedSettings(); state.driver=driver;
settings.write({thinkMs:7100,threads:4,hashMb:256,bookOwnMoves:0,winSeek:true,searchMode:"depth",searchDepth:18});
panel.refreshSharedSettings();
assert.strictEqual(state.movetimeMsKey,7100); assert.strictEqual(calls.Movetime,7100);
assert.strictEqual(calls.SearchDepth,18); assert.strictEqual(calls.BookOwnMoves,0);
assert.deepStrictEqual(calls.Pikafish,{threads:4,hashMb:256,contempt:40});
// A CLI/autorun override must survive an unrelated page setting change.
state.movetimeMsKey=9000;state.pikafishOptions.evalFile="custom.nnue";
settings.write({bookOwnMoves:5}); panel.refreshSharedSettings();
assert.strictEqual(calls.Movetime,9000);assert.strictEqual(calls.Pikafish.evalFile,"custom.nnue");
state.running=true; settings.write({mode:"advice",mySide:"b",tap:"shizuku"});panel.refreshSharedSettings();
assert(calls.stop); assert.strictEqual(state.modeKey,"advice"); assert.strictEqual(state.mySideKey,"b");
// The legacy panel widgets still write their original storage keys.
storage.put("thinkMs",8800); assert.strictEqual(settings.read().thinkMs,8800);

const bindings = {}, views = {};
const ui = new Proxy({}, {get(o,k){return views[k] || (views[k]={on(e,f){bindings[k]=f;},setText(t){this.text=t;},setEnabled(v){this.enabled=v;},setTextColor(){},setBackgroundColor(){}});}});
const page = require("../src/ui/page-settings.js").create(ui,()=>{},()=>{});
bindings.searchTime(); bindings.thinkPlus(); assert.strictEqual(settings.read().thinkMs,8900);
storage.put("threads",7);page.refresh();assert.strictEqual(ui.threadsValue.text,"7");
bindings.threadsPlus();assert.strictEqual(settings.read().threads,8);assert(!ui.threadsPlus.enabled);

let now=100, other={fresh:false}, sent=[], reports=[], opened=0;
const bus={readOther(){return other;},send(to,name,arg){sent.push({to,name,arg});return true;},stopOther(){sent.push({name:"stop"});}};
const remote=require("../src/app/page-actions.js").create({bus,baseDir:"/project",now:()=>now,open(){opened++;},report(s){reports.push(s);}});
remote.request("RECOGNIZE"); assert.strictEqual(opened,1);assert.strictEqual(sent.length,0);
other={fresh:true,state:{readyAt:99,baseDir:"/different"}};remote.pump();assert.strictEqual(sent.length,0);
other.state.baseDir="/project";remote.pump();assert.strictEqual(sent.length,1);
assert(!remote.request("START"));
other.state.lastPageAction={id:sent[0].arg.id,ok:false,reason:"no board"};remote.pump();assert(reports.at(-1).includes("no board"));
remote.request("START");remote.cancel();assert.strictEqual(sent.at(-1).name,"stop");
remote.request("RELOCATE");now+=46000;remote.pump();assert(reports.at(-1).includes("未收到"));

let commands=0;
const ps={readyAt:123,BASE_DIR:"/project",logLines:[],engineModule:{lastEngine(){return "pikafish";}},
    adapter:{captureBackend(){return "auto";}},bus:{publish(){}},mySideKey:"w"};
const pa={stepLine(){return "ready";},render(){},runPanelCommand(){commands++;ps.starting=true;return {ok:true};}};
const panelBus=require("../src/app/panel/bus.js").create(ps,pa);
panelBus.applyBusCommand({name:"pageAction",arg:{command:"START",id:"old",targetReadyAt:122,requestedAt:Date.now()}});
panelBus.applyBusCommand({name:"pageAction",arg:{command:"START",id:"late",targetReadyAt:123,requestedAt:Date.now()-13000}});
assert.strictEqual(commands,0,"stale start replayed into another panel instance");
panelBus.applyBusCommand({name:"pageAction",arg:{command:"START",id:"new",targetReadyAt:123,requestedAt:Date.now()}});
assert.strictEqual(commands,1);assert(ps.lastPageAction.pending,"controller drops pending: snapshot must read actual starting state");

// Layout contracts include IDs built by helpers, not just literal XML IDs.
const xml=require("../src/ui/page-layout.js").build("test");
for(const id of Object.keys(views)) assert(xml.includes('id="'+id+'"'),"missing page control "+id);
console.log("PAGE SETTINGS OK: ranges, migrations, persistence, driver application, both-way edits, autorun, action ack/cancel/timeout, layout bindings");
