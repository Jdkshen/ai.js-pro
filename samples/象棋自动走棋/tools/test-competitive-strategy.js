// @engine quickjs
"use strict";
const assert = require("assert");
const fen = require("../src/chess/fen.js"), legal = require("../src/chess/legal-moves.js");
const review = require("../src/engine/critical-review.js");
const winSeek = require("../src/automation/win-seek.js");
const driverDefaults = require("../src/automation/driver-defaults.js");
const start = fen.parseFen(fen.START_FEN).board;
// 2026-09-28 实战首兑车：+27 只是微优，旧 25cp 门槛把安全的保车候选关掉了。
const firstRookSwap = fen.parseFen("3akabr1/9/1c2b1n1c/r3p1pRp/pnp6/6P2/P1P1P3P/N1C1C1N2/3R5/2BAKAB2 w - - 0 1").board;
const swapChoices = winSeek.swapMoves(legal.allLegalMoves(firstRookSwap, "w"), firstRookSwap, "w");
assert(swapChoices.some(x => x.iccs === "h6h9"), "实战兑车未被识别");
const nearEqualLines = [{move:"h6h9",scoreCp:27,depth:16}, {move:"h6g6",scoreCp:13,depth:16}];
const seekOptions = {side:"w",slack:driverDefaults.winSeekSwapSlack,
    minScore:driverDefaults.winSeekScoreMin,maxScore:driverDefaults.winSeekScoreMax};
assert.strictEqual(winSeek.preferAlternative(nearEqualLines,["h6h9"],seekOptions).iccs,"h6g6",
    "微优局面应选同层、分差可控的非兑车着法");
// 2026-10-06：求胜窗口上限 80 → 250 —— 中优（+81）也要保留复杂度（别把领先简化掉）。
assert.strictEqual(winSeek.preferAlternative([{move:"h6h9",scoreCp:81,depth:16},
    {move:"h6g6",scoreCp:67,depth:16}],["h6h9"],seekOptions).iccs,"h6g6",
    "中优局面（+81）也应选非兑车候选，保留复杂度");
assert.strictEqual(winSeek.preferAlternative([{move:"h6h9",scoreCp:281,depth:16},
    {move:"h6g6",scoreCp:267,depth:16}],["h6h9"],seekOptions),null,
    "明显胜势（>250）时不应为了复杂度干预引擎");
// 75 回合实战：末段被黑马长将，未被将军时 f8g6 重回旧局面。
// MultiPV 到时第 2 路可能落后 1 层；同盘真机复算 h5g5 仅低 3cp。
const repeatOptions = Object.assign({},seekOptions,{onePlyRepeat:["f8g6"]});
assert.strictEqual(winSeek.preferAlternative([{move:"f8g6",scoreCp:10,depth:22},
    {move:"h5g5",scoreCp:6,depth:21}],["f8g6"],repeatOptions).iccs,"h5g5",
    "高深度只差一层、分差很小的非重复候选应可采用");
assert.strictEqual(winSeek.preferAlternative([{move:"f8g6",scoreCp:10,depth:22},
    {move:"h5g5",scoreCp:-39,depth:21}],["f8g6"],repeatOptions),null,
    "不能为了避和接受明显失分");
assert.strictEqual(winSeek.preferAlternative([{move:"f8g6",scoreCp:10,depth:22},
    {move:"h5g5",scoreCp:6,depth:20}],["f8g6"],repeatOptions),null,
    "相差两层的候选不可比");
assert.strictEqual(winSeek.preferAlternative([{move:"f8g6",scoreCp:10,depth:12},
    {move:"h5g5",scoreCp:6,depth:11}],["f8g6"],repeatOptions),null,
    "低深度的跨层评分不能用于改着");
// dev-723 实战 91 回合「没有进攻子力」：e2d2 是静着，却允许黑车 d3d2、红仕 e1d2 兑光最后一车。
const lastAttacker = fen.parseFen("9/3k5/9/9/9/9/3r5/4R4/4A1p2/2B1KAB2 w - - 0 1").board;
const lastMoves = legal.allLegalMoves(lastAttacker,"w");
assert(!winSeek.swapMoves(lastMoves,lastAttacker,"w").some(x => x.iccs === "e2d2"),
    "主动吃子检测不应假装识别静着兑车");
assert(winSeek.quietTradeMoves(lastMoves,lastAttacker,"w").some(x => x.iccs === "e2d2"),
    "最后进攻子力的静着兑车必须被识别");
assert.strictEqual(winSeek.quietTradeMoves(legal.allLegalMoves(start,"w"),start,"w").length,0,
    "正常开局不能把普通静着当成最后子力兑子");
assert.strictEqual(winSeek.preferAlternative([
    {move:"e2d2",scoreCp:11,depth:36},{move:"g0i2",scoreCp:11,depth:36}
],["e2d2"],seekOptions).iccs,"g0i2","同层等分时留住最后一车");
assert.strictEqual(winSeek.preferAlternative([
    {move:"e2d2",scoreCp:11,depth:36},{move:"g0i2",scoreCp:-90,depth:36}
],["e2d2"],seekOptions),null,"不能为了留车大幅失分");
const options = {criticalReview:true, movetime:5000, previousScore:180};
assert(review.plan(start,"w",{move:"h2e2",scoreCp:20},"",options).reason.includes("评分下降"));
assert(review.plan(start,"b",{move:"h7e7",scoreCp:20},"",Object.assign({},options,{previousScore:-180})));
assert.strictEqual(review.plan(start,"b",{move:"h7e7",scoreCp:20},"",options),null,"score sign inverted");
assert.strictEqual(review.plan(start,"w",{move:"h2e2",scoreCp:20},"",{criticalReview:true,previousScore:null}),null);
assert.strictEqual(review.plan(start,"w",{move:"h2e2",scoreCp:20},"",Object.assign({},options,{depth:18})),null);
assert.strictEqual(review.plan(start,"w",{move:"h2e2",scoreCp:20},"",Object.assign({},options,{criticalReview:false})),null);
assert.strictEqual(review.plan(start,"w",{move:"h2e2",scoreCp:20},"",Object.assign({},options,{movetime:20000})),null);
const trace = "info depth 8 multipv 1 score cp 10 pv h2e2\ninfo depth 9 multipv 1 score cp 0 pv b2e2\ninfo depth 10 multipv 1 score cp 0 pv h2e2";
assert(review.plan(start,"w",{move:"h2e2",scoreCp:20},trace,{criticalReview:true}).reason.includes("变化"));
assert.strictEqual(review.plan(start,"w",{move:"h2e2",scoreCp:20},trace.replace(/multipv 1/g,"multipv 2"),{criticalReview:true}),null);
assert.strictEqual(review.plan(start,"w",{move:"h2e2",mate:5},"",{criticalReview:true,movetime:14000}).extraMs,1000);

let calls=[], secondDepth=14, secondMove="b2e2", throwSecond=false;
global.$pikafish={available:()=>true,isRunning:()=>true,start:()=>({ok:true}),analyzePosition:(command,ms,timeout,multipv,depth)=>{
    calls.push({command,ms,timeout,multipv,depth});
    if(calls.length===2 && throwSecond) throw Error("review failure");
    const second=calls.length===2;
    return {ok:true,move:second?secondMove:"h2e2",raw:trace,candidates:[{
        move:second?secondMove:"h2e2",scoreCp:20,depth:second?secondDepth:12,pv:second?secondMove:"h2e2",timeMs:ms}]};
},analyze:()=>{throw Error("verified history lost");}};
const native=require("../src/engine/pikafish-engine.js");
const command="position fen "+fen.START_FEN;
function search(extra){calls=[];return native.analyze({board:start,side:"w"},Object.assign({},options,{positionCommand:command,historyStatus:"complete"},extra));}
let r=search();assert.strictEqual(r.iccs,"b2e2");assert(r.review.accepted);assert.strictEqual(calls.length,2);
assert(calls.every(c=>c.command===command));assert.strictEqual(calls[1].ms,6500);assert(r.historyUsed); // ★ 2026-10-06 关键步加时上限 3000→6500
secondDepth=10;r=search();assert.strictEqual(r.iccs,"h2e2");assert(!r.review.accepted);
secondDepth=14;secondMove="e0e9";r=search();assert.strictEqual(r.iccs,"h2e2");assert(!r.review.accepted);
secondMove="b2e2";r=search({excludeIccs:["b2e2"]});assert.strictEqual(r.iccs,"h2e2");assert(!r.review.accepted);
throwSecond=true;r=search();assert(r.ok && r.review.error);assert.strictEqual(r.iccs,"h2e2");throwSecond=false;
r=search({depth:18});assert.strictEqual(calls.length,1);assert.strictEqual(r.review,null);

const pre=require("../src/automation/prethink.js");
const afterOwn=legal.applyMove(start,{iccs:"h2e2"}).board;
const snap={position:{board:afterOwn,side:"b"},positionCommand:command+" moves h2e2"};
const p=pre.plan(snap,"h7e7","w",7,{threads:2},5000);
assert(p && p.budget===2500);assert(p.command.endsWith("moves h2e2 h7e7"));
assert.strictEqual(pre.plan(snap,"e9e0","w",7,{},5000),null);
assert.strictEqual(pre.plan({position:snap.position},"h7e7","w",7,{},5000),null);
let taskArgs, interrupts=0, clock=1000, starts=0;
function workerEnvironment(onJob){
    const listeners=new Map();let channel,active;
    const bus={on:(name,fn)=>listeners.set(name,fn),off:name=>listeners.delete(name),emit:(name,value)=>{
        if(value.type==="job"){active=value;taskArgs=value.payload;if(onJob)onJob(value.payload);}return true;
    }};
    return {dir:__dirname+"/..",nowMono:()=>clock,bus,schedule:()=>1,unschedule(){},
        spawn(args){starts++;channel=args.channel;
            listeners.get(channel+".result")({type:"ready"});
            return {getResult:()=>({status:"running"}),interrupt:()=>interrupts++};},
        complete(result){listeners.get(channel+".result")({type:"result",id:active.id,result});}};
}
const sw=require("../src/automation/search-worker.js");
const environment=workerEnvironment();
const cooperative=sw.create(environment),pondering=pre.create(cooperative);
assert(pondering.start(p,{threads:2},"pikafish","complete"));
assert.strictEqual(taskArgs.openingBook,false);assert.strictEqual(taskArgs.criticalReview,false);
assert.strictEqual(taskArgs.positionCommand,p.command);
assert(pondering.busy());pondering.cancel();assert.strictEqual(interrupts,0,"JNI context interrupted");
assert(!pondering.start(p,{},"pikafish","complete"),"overlapping native task");
environment.complete({ok:true,iccs:"a0a1"});pondering.poll();
assert(!pondering.busy());assert(!pondering.take(p.key),"cancelled result reused");assert.strictEqual(interrupts,0);
assert(pondering.start(p,{threads:2},"pikafish","complete"));
environment.complete({ok:true});pondering.poll();
assert(!pondering.take(p.key+"new-session"));assert(!pondering.take(p.key),"stale entry remained");
assert(pondering.start(p,{threads:2},"pikafish","complete"));
environment.complete({ok:true});assert(pondering.take(p.key));assert(!pondering.take(p.key));
assert.strictEqual(starts,1,"prethink must reuse the same persistent worker");
assert.notStrictEqual(p.key,pre.identity(fen.parseFen(p.position).board,"w",p.command,8,{threads:2}));
assert.notStrictEqual(p.key,pre.identity(fen.parseFen(p.position).board,"w",p.command,7,{threads:4}));
cooperative.close();

// Production search payload: book gates + review + history + metadata.
const env2=workerEnvironment(),worker=sw.create(env2);
worker.start(fen.START_FEN,{bookOwnMoves:2,ownMovesMade:2,bookRandom:false,
    criticalReview:true,previousScore:100,openingLearning:true,positionCommand:command,historyStatus:"complete"});
const router=require("../src/engine/engine-router.js"), saved=router.analyze;
let forwarded;
router.analyze=(position,opts)=>{forwarded=opts;return {ok:true,review:{accepted:true},excludeRelaxed:true,bookLearned:true};};
const transported=require("../src/automation/search-task.js").analyze(taskArgs,router);
assert.strictEqual(forwarded.bookOwnMoves,2);assert.strictEqual(forwarded.ownMovesMade,2);
assert.strictEqual(forwarded.bookRandom,false);assert.strictEqual(forwarded.criticalReview,true);
assert.strictEqual(forwarded.previousScore,100);assert.strictEqual(forwarded.openingLearning,true);
assert.strictEqual(forwarded.positionCommand,command);assert(transported.review.accepted && transported.excludeRelaxed && transported.bookLearned);
router.analyze=saved;worker.close();

// Append-only game records; outcome correction replaces a separate key, never adds another game.
const records=require("../src/services/opening-results.js");
const data={}, storage={get:(k,d)=>k in data?JSON.parse(JSON.stringify(data[k])):d,put:(k,v)=>{data[k]=JSON.parse(JSON.stringify(v));}};
global.storages={create:()=>storage};
const tracker=require("../src/services/position-tracker.js").createPositionTracker();
tracker.adopt({board:start,side:"w"});
const session=records.createSession({storage});session.update(tracker.snapshot(),"w",{});
tracker.confirmMove({fromBoard:start,side:"w",iccs:"h2e2"});session.update(tracker.snapshot(),"w",{});
session.update(tracker.snapshot(),"w",{});
let games=records.list(storage);assert.strictEqual(games.length,1);const gameId=games[0].id;
assert.strictEqual(records.result(gameId,"win",storage).ok,true);
tracker.confirmMove({fromBoard:afterOwn,side:"b",iccs:"h7e7"});session.update(tracker.snapshot(),"w",{});
assert.strictEqual(records.list(storage)[0].outcome,"win","late observation erased result");
records.result(gameId,"loss",storage);assert.strictEqual(records.list(storage).length,1);
const key=fen.layoutOf(start)+" w";
let stats=records.aggregate(records.list(storage),records.PROFILE,"w");assert.strictEqual(stats[key].h2e2.points,0);
assert.deepStrictEqual(records.aggregate(records.list(storage),"another-opponent","w"),{});
assert.deepStrictEqual(records.aggregate(records.list(storage),records.PROFILE,"b"),{});
tracker.fix({board:afterOwn,side:"b"});session.update(tracker.snapshot(),"w",{});
assert.deepStrictEqual(records.aggregate(records.list(storage),records.PROFILE,"w"),{},"partial game used for learning");
for(let i=0;i<32;i++){session.reset();tracker.adopt({board:start,side:"w"});session.update(tracker.snapshot(),"w",{});}
assert.strictEqual(records.list(storage).length,33,"history silently truncated");
const pool=[{iccs:"h2e2"},{iccs:"b2e2"}];
assert.strictEqual(records.choose(pool,{h2e2:{games:5,points:5},b2e2:{games:4,points:0}}),null);
assert.strictEqual(records.choose(pool,{h2e2:{games:5,points:5},b2e2:{games:5,points:0}}),"h2e2");
assert.strictEqual(records.choose(pool,{h2e2:{games:5,points:3},b2e2:{games:5,points:3}}),null);
const book=require("../src/engine/opening-book.js");
const entry={move:"h2e2",candidates:[{move:"h2e2",scoreType:"cp",score:30},{move:"b2e2",scoreType:"cp",score:10},{move:"a0a1",scoreType:"cp",score:-80}]};
const evidence={h2e2:{games:5,points:0},b2e2:{games:5,points:5},a0a1:{games:100,points:100}};
assert.strictEqual(book.pickLine(entry,{results:evidence}).iccs,"b2e2");
assert.strictEqual(book.pickLine(entry,{results:evidence,deterministic:true}).iccs,"h2e2");
assert.strictEqual(book.pickLine(entry,{results:evidence,excludes:["b2e2"]}).iccs,"h2e2");
// Production driver replay: speculate only after confirmation; actual history reaches
// every foreground search. A busy prediction delays dispatch instead of overlapping JNI.
const driverModule=require("../src/automation/auto-driver.js");
const geometry=require("../src/vision/geometry.js");
["hit","miss","drain","pause","new-session"].forEach(scenario=>{
    let time=10000, board=start, taps=0, jobs=0, bg={status:"running"}, interrupted=0;
    const searches=[], notes=[];
    const own=legal.allLegalMoves(start,"w")[0];
    const after=legal.applyMove(start,own).board;
    const replies=legal.allLegalMoves(after,"b");
    let args;
    const env=workerEnvironment(a=>{jobs++;args=a;});env.nowMono=()=>time;
    const backgroundWorker=sw.create(env);
    const grid=geometry.createGrid({originX:96,originY:782,stepX:136,stepY:135,
        orientation:geometry.ORIENTATION_RED_BOTTOM,version:7,
        quality:{sampleCount:20,inlierCount:20,maxResidual:0.05,meanResidual:0.02}});
    const d=driverModule.createDriver({searchWorker:backgroundWorker,allowClick:true,log:x=>notes.push(x),sleep:()=>{},nowMono:()=>time,
        screen:()=>({width:1080,height:2400}),locate:()=>({ok:true,grid}),
        recognize:()=>({ok:true,chars:board.slice(),unknownCells:0,conflicts:0,grid}),
        analyze:(pos,opts)=>{
            searches.push(opts); const m=legal.allLegalMoves(pos.board,pos.side)[0];
            return {ok:true,iccs:m.iccs,move:m,score:0,depth:12,ms:500,engine:"pikafish",pvIccs:[m.iccs,replies[0].iccs]};
        },tap:(x,y,o)=>{taps++;if(o.kind==="to")board=after;return {ok:true,method:"replay"};},
        config:{computeMode:"inline",movetimeMs:500,observeFrames:1,prethink:true}});
    d.start();
    for(let i=0;i<50 && jobs===0;i++){time+=100;d.tick();}
    assert.strictEqual(jobs,1,scenario+": no background search after confirmed own move: "+notes.slice(-8));
    assert.strictEqual(d.tracker().ledger.ply,1);assert.strictEqual(d.ownMoveCount(),1,"duplicate confirmation consumed book budget");
    assert.strictEqual(searches.length,1);assert.strictEqual(d.isThinking(),false,"background changed capture cadence");
    assert(args.positionCommand.endsWith(" moves "+own.iccs+" "+replies[0].iccs));
    const beforeTaps=taps;
    if(scenario==="pause" || scenario==="new-session") {
        if(scenario==="pause")d.pause();else d.newSession();
        env.complete({ok:true,iccs:"a0a1"});
        for(let i=0;i<3;i++){time+=100;d.tick();}
        assert.strictEqual(taps,beforeTaps,"stale background result produced a click");
        assert.strictEqual(interrupted,0,"native worker was interrupted during cancellation");
        d.stop();d.close();return;
    }
    const reply=scenario==="miss"?replies[1]:replies[0];
    board=legal.applyMove(after,reply).board;
    if(scenario!=="drain") env.complete({ok:true,iccs:"a0a1"});
    for(let i=0;i<30 && searches.length<2 && !d.isThinking();i++){time+=100;d.tick();}
    if(scenario==="drain") {
        assert(d.isThinking());assert.strictEqual(searches.length,1,"foreground overlapped JNI prediction");
        env.complete({ok:true,iccs:"a0a1"});
        for(let i=0;i<8 && searches.length<2;i++){time+=100;d.tick();}
    }
    assert.strictEqual(searches.length,2,scenario+": foreground never resumed: "+notes.slice(-8));
    assert(searches[1].positionCommand.endsWith(" moves "+own.iccs+" "+reply.iccs));
    assert.strictEqual(notes.some(x=>x.includes("手机预思考命中")),scenario==="hit");
    assert.strictEqual(interrupted,0);d.stop();d.close();
});
delete global.threads;delete global.__args;
console.log("COMPETITIVE STRATEGY OK: critical review, score sides, history/exclusions, depth/failed review fallback; cooperative background drain, stale key rejection; worker option transport; results correction, partial/profile/side isolation, 33-game retention and safe book ranking");
