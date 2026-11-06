'use strict';
const assert=require('assert'),path=require('path'),fs=require('fs'),vm=require('vm');
const root=path.resolve(__dirname,'../../apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋');
const load=f=>require(path.join(root,'src',f));
const fen=load('chess/fen.js'),legal=load('chess/legal-moves.js');
// Exact K40 cannon capture. Removing the captured destination first makes this
// cannon capture illegal (the screen is still present, but the target is gone).
const before=fen.parseFen('r2akabn1/3r5/1cn1b4/p1C1p1p1p/3P5/4c1PC1/P7P/6N2/9/RNBAKABR1 b - - 0 1').board;
const after=legal.applyMove(before,{iccs:'e4h4'}).board;
const seen=after.slice();seen[49]='?';
const broken=before.slice();broken[52]=null;
const facts=load('automation/driver/recognition.js').create({lastReliable:{board:broken,side:'b'},stats:{}},{});
assert.strictEqual(facts.fillKnownFacts(seen),null,'modified baseline cannot explain this capture');
const filled=facts.fillKnownFacts(seen,{board:before,side:'b'});
assert.deepStrictEqual(filled.board,after);assert.match(filled.how,/e4h4/);
assert.strictEqual(before[52],'C','immutable pre-repair baseline retains the captured piece');

const grid={originX:50,originY:200,stepX:100,stepY:90,version:1,orientation:'RED_BOTTOM'};
let locates=0,now=10000;
const driver=load('automation/auto-driver.js').createDriver({allowClick:true,nowMono:()=>now,sleep(){},
 locate:()=>{locates++;return {ok:true,grid};},
 recognizeAsync:()=>({ok:false,geometryChanged:true,reason:'window invalid'}),
 recognize:()=>({ok:false,reason:'window invalid'}),
 analyze:()=>assert.fail('no search on invalid capture'),
 screen:()=>({width:1080,height:2400}),tap:()=>assert.fail('no click on invalid capture'),
 config:{observeFrames:1,prethink:false,winSeek:false}});
driver.start();driver.tick();
assert.strictEqual(driver.machine.state(),'RECOVERING');
assert.strictEqual(driver.machine.geometryTrusted(),false);
now+=5000;driver.tick();
assert.ok(locates>=2,'geometry failure triggers recovery without waiting for a blind-read watchdog');
driver.stop();

// Parent calibration must rebind even when the trusted grid and window ID have
// exactly the same values. The invalid worker must also invalidate main capture.
global.threads={start(){}};global.files={write(){}};
const window={windowId:7,packageName:'game',bounds:{left:0,top:0,right:1080,bottom:2400},rotation:0};
let invalidations=0,cancels=0,jobs=[];
const state={grid,BASE_DIR:root,adapter:{captureBackend:()=> 'window',windowCaptureInfo:()=>window,
 invalidateWindowCaptureGeometry:()=>invalidations++},visionWorker:{
 step:(key,job)=>{jobs.push({key,job});return {result:{ok:false,geometryChanged:true,windowCaptureInvalid:true},ms:10};},
 cancel:()=>cancels++,stats:()=>({workerStarts:1})}};
const actions=load('app/panel/async-vision.js').create(state,{logLine(){}});
const req={stamp:{sessionId:1,state:'WAIT_STABLE'}};
actions.observeAsync(req,{});actions.cancelVision();actions.observeAsync(req,{});
assert.strictEqual(invalidations,2);assert.strictEqual(cancels,1);
assert.notStrictEqual(jobs[0].key,jobs[1].key);
assert.strictEqual(jobs[1].job.calibrationEpoch,1);
delete global.threads;delete global.files;

// Execute the actual child loop with a sticky capture adapter. The first two
// jobs have the same binding; only the parent's new calibration epoch releases
// the latch for job 3. A changed window without calibration still gets rejected.
let latch=false,resetCount=0,index=0,captures=0,currentInfo=window;
const packets=[0,0,1,1].map((epoch,i)=>({id:i+1,key:'job'+i,request:{grid,window,
 calibrationEpoch:epoch,stamp:{sessionId:1},options:{},config:{}}}));
const replies=[];
const adapter={setCaptureBackend(){latch=false;resetCount++;},capture(){
 captures++;if(captures===2)latch=true;
 if(latch)throw new Error('目标窗口位置或方向已改变，请点定位校准；旧坐标已停用');
 if(captures===4)currentInfo=Object.assign({},window,{bounds:Object.assign({},window.bounds,{top:10})});
 return {};},windowCaptureInfo:()=>currentInfo,captureChannel:()=> 'window',releaseFrame(){}};
const modules={
 './aijs-adapter.js':adapter,
 '../vision/pipeline-factory.js':{createSamples:()=>()=>[],createOcrHolder:()=>({get(){},release(){}}),
 createGlyphSource:()=>({release(){}})},
 '../vision/locate-board.js':{configureSift(){},releaseSift(){},locateBoardInImage:()=>({ok:true,grid}),checkGeometry:()=>({ok:true})},
 '../vision/sift-board-locator.js':{sameGrid:()=>true},
 '../vision/frame-gate.js':{create:()=>({})},
 '../services/board-service.js':{createBoardService:opts=>({setGrid(){},resetFrameBaseline(){},lastTiming:()=>({}),
 recognizeSmart(){const frame=opts.ports.capture();const checked=opts.checkGeometry(frame.image,grid);
 return checked.ok?{ok:true,chars:before.slice(),rawChars:before.slice(),unknownCells:0,grid,frame}:checked;}})},
 '../automation/driver/recognition.js':{create:s=>({recognizeMerged:o=>s.opts.recognize(o)})},
 '../app/panel/capture.js':{create:(s,a)=>({grabStableFrame(){s.recentFrame=a.grabFrameRaw();return s.recentFrame;},
 grabSettledFrame(){s.recentFrame=a.grabFrameRaw();return s.recentFrame;},clearRecentFrame(){s.recentFrame=null;}})},
 '../app/panel/grid-check.js':{create:()=>({probeGameOver:()=>false})}
};
const sandbox={module:{exports:{}},require:id=>{assert.ok(modules[id],id);return modules[id];},Date,console,
 sleep:()=>assert.fail('unexpected poll'),files:{read:f=>f==='control'?JSON.stringify({aliveAt:Date.now(),stop:index>=packets.length}):JSON.stringify(packets[index]),
 write:(f,text)=>{assert.strictEqual(f,'result');replies.push(JSON.parse(text));index++;},remove(){}}};
vm.runInNewContext(fs.readFileSync(path.join(root,'src/runtime/vision-task.js'),'utf8'),sandbox);
sandbox.module.exports.serve({dir:root,requestFile:'request',resultFile:'result',controlFile:'control'});
assert.deepStrictEqual(replies.map(r=>r.result.ok),[true,false,true,false]);
assert.strictEqual(resetCount,3,'initial setup and two binding revisions only');
assert.strictEqual(replies[1].result.windowCaptureInvalid,true);
assert.strictEqual(replies[3].result.geometryChanged,true,'uncalibrated shifted frame remains unusable');
console.log('CHESS_WINDOW_RECOVERY OK: same-grid recalibration releases worker latch, invalid frame blocks taps, K40 cannon capture retains inference baseline');
