'use strict';
const assert = require('assert');
const path = require('path');
const root = path.resolve(__dirname, '../../apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋');
const worker = require(path.join(root, 'src/runtime/vision-worker.js'));
const store = new Map();
let now = 1000, starts = 0;
const io = {write:(p,v) => store.set(p,v),read:p => store.get(p)};
const handle = worker.create({prefix:'job',dir:root,now:()=>now,files:io,
    spawn:() => {starts++;return {getResult:()=>({status:'running'})};}});
const request = () => JSON.parse(store.get('job.request.json'));
const finish = (r,at=now) => io.write('job.result.json',JSON.stringify({id:r.id,key:r.key,
    result:{ok:true},ms:100,capturedAt:at,finishedAt:now}));
assert.strictEqual(handle.step('session1',{}).pending,true);
const first = request();
handle.step('session2',{});
const second = request();
finish(first);
assert.strictEqual(handle.step('session2',{}).pending,true,'old session cannot supply the new board');
now+=2000;handle.keepAlive();
assert.strictEqual(JSON.parse(store.get('job.control.json')).canceledThrough,first.id,
    'heartbeat must preserve cancellation across an in-flight capture');
finish(second);
assert.strictEqual(handle.step('session2',{}).result.ok,true);
handle.step('session2',{});const third=request();
finish(third,now-1900);
assert.strictEqual(handle.step('session2',{}).pending,true,'old capture rejected even if completion is fresh');
handle.step('session2',{});const fourth=request();
handle.cancel();finish(fourth);
assert.strictEqual(handle.step('session2',{}).pending,true,'stop/restart rejects completed canceled work');
now+=12001;
assert.strictEqual(handle.step('session2',{}).ok,false,'bounded timeout');
assert.strictEqual(starts,1,'reuse one worker after changes/cancellation');
handle.close();
assert.strictEqual(JSON.parse(store.get('job.control.json')).stop,true);
let terminal=false, writesAfterExit=0;
const exitStore=new Map();
const dead=worker.create({prefix:'dead',dir:root,now:()=>now,
    files:{write:(p,v)=>{if(terminal)writesAfterExit++;exitStore.set(p,v);},
        read:p=>exitStore.get(p),remove:p=>exitStore.delete(p)},
    spawn:()=>({getResult:()=>({status:terminal?'success':'running'})})});
dead.step('one',{});terminal=true;
assert.throws(()=>dead.step('two',{}),/已退出/);
assert.strictEqual(writesAfterExit,0,'finished worker cannot leave a new orphan request or heartbeat');
assert.strictEqual(exitStore.size,0,'clean files left after an unexpected worker exit');

const driverModule = require(path.join(root,'src/automation/auto-driver.js'));
const fen = require(path.join(root,'src/chess/fen.js'));
const layout='rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR';
const board=fen.parseFen(layout+' w - - 0 1').board;
const grid={originX:50,originY:200,stepX:100,stepY:90,version:1,orientation:'RED_BOTTOM'};
let pending=true,syncCalls=0,taps=0,requests=[];
function recognize(){syncCalls++;return {ok:true,chars:board.slice(),layout,unknownCells:0,grid};}
const driver=driverModule.createDriver({allowClick:true,nowMono:()=>now,sleep(){},
    locate:()=>({ok:true,grid}),recognize,
    recognizeAsync:r=>{requests.push(r);return pending?{pending:true,elapsedMs:500,reason:'working'}:recognize();},
    analyze:()=>({ok:true,iccs:'c3c4',score:10,depth:10,ms:20,pvIccs:['c3c4','g6g5']}),
    screen:()=>({width:1080,height:2400}),tap:()=>{taps++;return {ok:true};},
    config:{movetimeMs:20,observeFrames:1,bookOwnMoves:0,computeMode:'inline',prethink:false,winSeek:false}});
driver.start();
const before=driver.stats();
for(let i=0;i<3;i++){
    const r=driver.tick();assert.strictEqual(r.observing,true);assert.strictEqual(r.ok,true);
}
assert.strictEqual(driver.stats().unrecognized,before.unrecognized);
assert.strictEqual(driver.stats().observations,before.observations,'polling cannot increment completed observations');
assert.strictEqual(taps,0);assert.strictEqual(syncCalls,0);
assert.ok(requests[0].stamp.sessionId!=null && requests[0].stamp.geometryVersion!=null);
pending=false;driver.tick();driver.assertTurn();
for(let i=0;i<5 && taps<2;i++) {now+=100;driver.tick();}
assert.strictEqual(taps,2,'accepted result permits exactly two ordered taps');
const verifyCalls=syncCalls;pending=true;driver.tick();
assert.ok(syncCalls>verifyCalls,'verification uses current synchronous capture, never a pending old result');
driver.stop();
console.log('CHESS_ASYNC_VISION OK: one worker, cancellation, stale capture, pending observations, ordered execution and synchronous verification');
