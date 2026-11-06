'use strict';
const assert=require('assert'),path=require('path');
const root=path.resolve(__dirname,'../../apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋');
const laneModule=require(path.join(root,'src/runtime/task-worker.js'));
const service=require(path.join(root,'src/runtime/task-service.js'));
const search=require(path.join(root,'src/automation/search-worker.js'));
const analyze=require(path.join(root,'src/automation/search-task.js')).analyze;
const ioQueue=require(path.join(root,'src/runtime/io-queue.js'));
const ioTask=require(path.join(root,'src/runtime/io-task.js'));

function harness(run){
    let now=1000,starts=0,quit=0,terminal=false;
    const listeners=new Map(),messages=[],timers=new Set();
    const bus={on(n,fn){listeners.set(n,fn);},off(n){listeners.delete(n);},
        emit(n,p){if(!listeners.has(n))return false;messages.push({n,p:JSON.parse(JSON.stringify(p))});return true;}};
    function schedule(fn){timers.add(fn);return fn;}
    function unschedule(fn){timers.delete(fn);}
    function step(){const m=messages.shift();if(!m)return;const fn=listeners.get(m.n);if(fn)fn(m.p);}
    function flush(){let guard=0;while(messages.length){assert.ok(++guard<1000);step();}}
    const options={dir:root,bus,now:()=>now,schedule,unschedule,
        spawn(args){starts++;service.start(args,{bus,now:()=>now,schedule,unschedule,run,
            exit(){quit++;terminal=true;}});return {getResult:()=>({status:terminal?'success':'running'})};}};
    return {options,flush,step,bus,messages,timers,advance(ms){now+=ms;Array.from(timers).forEach(fn=>fn());},
        get starts(){return starts;},get quit(){return quit;}};
}

// Long-lived search and full option/result propagation.
let calls=[];
const h=harness(payload=>{calls.push(payload);return {ok:true,iccs:payload.position,pvIccs:['a0a1','a9a8']};});
const worker=search.create(Object.assign({},h.options,{nowMono:h.options.now}));
assert.strictEqual(worker.start('a0a1',{movetime:100,openingBook:false,criticalReview:true,
    positionCommand:'position fen fixture moves a3a4',multiPv:3,bookRandom:false,maxTotalMs:400}),true);
assert.strictEqual(worker.poll().status,'running');h.flush();
assert.strictEqual(worker.poll().result.iccs,'a0a1');
assert.strictEqual(calls[0].criticalReview,true);assert.strictEqual(calls[0].openingBook,false);
assert.strictEqual(calls[0].multiPv,3);assert.strictEqual(calls[0].maxTotalMs,400);
for(let i=0;i<8;i++){worker.start('b0b'+i,{movetime:100});h.flush();assert.strictEqual(worker.poll().status,'done');}
assert.strictEqual(h.starts,1);assert.strictEqual(worker.isRunning(),false);
worker.close();h.flush();assert.strictEqual(h.quit,1);

// Cancel an in-flight old job; queue a new one, ignore the old answer and keep one worker.
const c=harness(p=>({ok:true,iccs:p.position}));
const cw=search.create(Object.assign({},c.options,{nowMono:c.options.now}));
cw.start('old',{});
// Deliver ready, control and old job; leave the old result waiting for the parent.
c.step();c.step();c.step();
cw.cancel();cw.start('new',{});c.flush();
assert.strictEqual(cw.poll().result.iccs,'new');assert.strictEqual(c.starts,1);
cw.close();c.flush();

// A dropped/blocked job times out without killing JNI or respawning a worker.
let timeoutNow=1000,spawned=0,stopped=0;const subs=new Map();
const idleBus={on:(n,f)=>subs.set(n,f),off:n=>subs.delete(n),emit:()=>true};
const t=laneModule.create({dir:root,task:'search',channel:'timeout',bus:idleBus,now:()=>timeoutNow,
    schedule:()=>1,unschedule(){},spawn(){spawned++;return {getResult:()=>({status:'running'}),interrupt(){stopped++;}};}});
const id=t.submit({position:'old'},1000);subs.get('timeout.result')({type:'ready'});
timeoutNow+=1001;assert.strictEqual(t.take(id).status,'error');
assert.strictEqual(t.submit({position:'new'},1000),null);assert.strictEqual(spawned,1);assert.strictEqual(stopped,0);
t.close();

// IO serializes appends and JSON writes; no main-thread file writes on the normal lane.
const disk=new Map(),writes=[];
const f={write(p,s){disk.set(p,s);writes.push(['write',p]);},append(p,s){disk.set(p,(disk.get(p)||'')+s);writes.push(['append',p]);}};
const qh=harness(p=>ioTask.run(p,f));
const q=ioQueue.create(Object.assign({},qh.options,{background:true,files:{write(){throw Error('main write');},append(){throw Error('main append');}}}));
q.append('log','A\n','HEADER\n');q.append('log','B\n');q.write('diag','{"ok":true}');qh.flush();
assert.strictEqual(disk.get('log'),'HEADER\nA\nB\n');assert.strictEqual(disk.get('diag'),'{"ok":true}');
assert.strictEqual(qh.starts,1);
// Close with queued jobs, without running any further parent timers/results.
q.append('log','C\n');q.append('log','D\n');q.close();qh.flush();
assert.strictEqual(disk.get('log'),'HEADER\nA\nB\nC\nD\n');assert.strictEqual(qh.quit,1);
assert.strictEqual(writes.filter(x=>x[0]==='append').length,4,'shutdown batch must not duplicate active append');

// Service replays acknowledgements for duplicate IDs, without performing IO twice.
let runs=0;const dh=harness(()=>{runs++;return {ok:true};});
const d=laneModule.create(Object.assign({},dh.options,{task:'io',channel:'duplicate'}));
d.submit({value:1},1000);dh.flush();dh.bus.emit('duplicate',{type:'job',id:1,payload:{value:1}});dh.flush();
assert.strictEqual(runs,1);d.close(true);dh.flush();

// Missing bus falls back to synchronous files; explicit startup-exit keeps unsent logs.
const inline=ioQueue.create({background:false,files:f});inline.append('fallback','one');inline.close();
assert.strictEqual(disk.get('fallback'),'one');assert.strictEqual(inline.write('fallback','two'),false);
const early=harness(p=>ioTask.run(p,f));
const earlyQueue=ioQueue.create(Object.assign({},early.options,{background:true,files:f}));
earlyQueue.append('early','last log','HEADER\n');earlyQueue.close();early.flush();
assert.strictEqual(disk.get('early'),'HEADER\nlast log','startup exit must preserve unsent text');
assert.strictEqual(early.quit,1);

// The production payload adapter must retain PV, review, history and native statistics.
const returned={ok:true,iccs:'e0f0',pvIccs:['e0f0','c7c0'],candidates:[{iccs:'e0f0'}],
    historyUsed:true,historyStatus:'verified',review:{extended:true},ms:200,nodes:77};
const actual=analyze({dir:root,position:'fixture',openingBook:false,criticalReview:true,maxTotalMs:800},
    {setBackend(){},configurePikafish(){},analyze(p,o){assert.strictEqual(o.openingBook,false);assert.strictEqual(o.maxTotalMs,800);return returned;},lastEngine:()=> 'pikafish'});
assert.deepStrictEqual(actual.pvIccs,returned.pvIccs);assert.deepStrictEqual(actual.review,returned.review);
assert.strictEqual(actual.historyUsed,true);assert.strictEqual(actual.nodes,77);

// Rebuilding a panel driver retains its search lane and drains old work first.
const sessionModule=require(path.join(root,'src/services/game-session.js'));
let fakeStops=0,closes=0,passedWorker;
const shared={close(){closes++;}};
const session=sessionModule.createGameSession({driverModule:{createDriver(deps){passedWorker=deps.searchWorker;
    return {searchWorker:passedWorker||shared,stop(){fakeStops++;},start(){return 'WAIT_STABLE';}};}}});
session.build();session.discard();session.build();assert.strictEqual(passedWorker,shared);
assert.strictEqual(fakeStops,1);session.close();assert.strictEqual(closes,1);

// QuickJS exit() has to send close messages before native exit tears down the bus.
const lifecycle=require(path.join(root,'src/app/panel/lifecycle.js'));
const oldEvents=global.events,oldExit=global.exit;let exitListener,shutdowns=0,flushed=0,nativeExits=0;
global.events={on(name,fn){exitListener=fn;}};global.exit=()=>{nativeExits++;return 'native exit';};
const ls={app:{session:{close(){shutdowns++;}}},adapter:{overlayHide(){}}};
const la={closePanelFiles(){flushed++;},paramStore:()=>null,closeVision(){},clearRecentFrame(){},releaseOcr(){},logLine(){}};
lifecycle.create(ls,la).registerExitHook();
assert.strictEqual(global.exit(),'native exit');exitListener();
assert.strictEqual(shutdowns,1);assert.strictEqual(flushed,1);assert.strictEqual(nativeExits,1);
global.events=oldEvents;if(oldExit===undefined)delete global.exit;else global.exit=oldExit;
console.log('CHESS_TASK_WORKERS OK: persistent search, option/PV propagation, cancellation, bounded timeout, ordered IO, duplicate protection and shutdown drain');
