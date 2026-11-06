'use strict';
const assert=require('assert'), path=require('path');
const root=path.resolve(__dirname,'../../apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋');
const load=f=>require(path.join(root,'src',f));
const safety=load('automation/observation-safety.js');
const glyph=load('vision/glyph-recognizer.js');
const fen=load('chess/fen.js'), legal=load('chess/legal-moves.js');
const actual=fen.parseFen('4kab2/4a4/2c1bnc1n/p3R1p1p/9/2p3P2/P1P1P3P/Nr1CB1N2/4A1C2/2BAKr3 w - - 0 1').board;
const old=actual.slice();old[86]='R';
const held=glyph.preserveIdentityOnSameOccupancy(actual,old);
assert.strictEqual(held.board[86],'r','K40 static black rook must never become a historical red rook');
assert.strictEqual(actual[86],'r','raw image result stays immutable');
assert.strictEqual(legal.isLegalMove(actual,4,3,0,3,'w').legal,false,'K40 e6a6 ignores the check');
assert.strictEqual(legal.isLegalMove(actual,4,9,5,9,'w').legal,true,'king can take the checking rook');
for(const ch of ['K','k']) {
    const gone=actual.slice();gone[gone.indexOf(ch)]=null;
    assert.strictEqual(safety.inspect(gone).unsafe,true);
    const guard=safety.createGuard();
    const r={ok:false,rawChars:gone,frame:{window:{frameId:20}}};
    assert.strictEqual(guard.check(r,1,1).confirmed,false);
    assert.strictEqual(guard.check(r,1,2).confirmed,false,'same frame cannot confirm disappearance');
    r.frame.window.frameId++;
    assert.strictEqual(guard.check(r,1,3).confirmed,true);
    assert.strictEqual(guard.check({rawChars:actual},1,4),null,'fresh complete kings clear a transient miss');
    assert.strictEqual(guard.check(r,2,5).confirmed,false,'new session resets missing king votes');
}
const recognition=load('automation/driver/recognition.js');
let calls=0;
const gone=actual.slice();gone[85]=null;
const merge=recognition.create({cfg:{observeFrames:2,observeGapMs:0},clock(){},stats:{},
    opts:{recognize(){return ++calls===1 ? {ok:true,chars:actual.slice()} : {ok:false,rawChars:gone};}}},{});
assert.strictEqual(merge.recognizeMerged({}).royalUnsafe,true,'second missing-king frame forbids fallback to first valid frame');
const before=fen.parseFen('4kab2/4a4/2c1bnc1n/p3R3p/6p2/2p3P2/P1P1P3P/Nr1CB1N2/4A1C2/2BA1K3 w - - 0 1').board;
const expected=legal.applyMove(before,{iccs:'c3c4'}).board;
const reply=legal.applyMove(expected,{iccs:'g5g4'}).board;
const ghost=reply.slice();ghost[42]='p';
const verifier=load('automation/move-verifier.js');
const ticket={actionId:'reply-test',moveICCS:'c3c4',baseSide:'w',baseFen:fen.buildFen(before,'w'),
    expectedAfterFen:fen.buildFen(expected,'b')};
assert.strictEqual(verifier.verify({ticket,observed:ghost,unknownCells:0,stable:true,geometryTrusted:true}).result,'UNRELIABLE');
let final;
const verification=load('automation/driver/verification.js');
const state={stats:{confirms:0},cfg:{},verifyCycle:0,decisionEpoch:0,lastMoves:{},nowMono:()=>1000,
    lastResult:{glyphStats:{circleEvidence:{active:true}}},machine:{dispatch:e=>{final=e;return {effects:[]};}}};
const actions=new Proxy({tryFastConfirmRoI:()=>null,
    observeOnce:()=>({ok:true,chars:ghost,rawChars:reply,unknownCells:0}),applyKnownPatchesTo:b=>b,
    boardOfFen:text=>fen.parseFen(text).board,replyCorroborated:()=>true,
    countUnknownChars:b=>b.filter(c=>c==='?').length,pump:o=>o},
    {get:(target,key)=>target[key] || (()=>{})});
verification.create(state,actions).doVerify({ticket});
assert.strictEqual(final.verdict.result,'OPPONENT_REPLY','K40 complete raw reply supersedes invented historical source');
assert.deepStrictEqual(state.lastReliable.board,reply);
let rawFrames=0;
const rawMerge=recognition.create({cfg:{observeFrames:2,observeGapMs:0},clock(){},stats:{},opts:{recognize(){
    const raw=actual.slice();if(++rawFrames===2)raw[42]='p';
    return {ok:true,chars:actual.slice(),rawChars:raw};
}}},{}).recognizeMerged({});
assert.strictEqual(rawMerge.rawChars[42],'?','raw confirmation requires agreement of both captured frames');
const driverModule=load('automation/auto-driver.js');
function run(board,raw,config={}) {
    let now=1000,taps=0,frame=0;
    const grid={originX:50,originY:200,stepX:100,stepY:90,version:1,orientation:'RED_BOTTOM'};
    const driver=driverModule.createDriver({allowClick:true,nowMono:()=>now,sleep(){},
        locate:()=>({ok:true,grid}),recognize:()=>({ok:true,chars:board.slice(),rawChars:raw.slice(),
            unknownCells:0,grid,frame:{frameId:++frame},glyphStats:{circleEvidence:{active:true,sides:{86:'BLACK'}}}}),
        analyze:()=>({ok:true,iccs:'e6a6',score:716,depth:19,ms:20}),screen:()=>({width:1080,height:2400}),
        tap:()=>{taps++;return {ok:true};},config:Object.assign({computeMode:'inline',prethink:false,
            winSeek:false,bookOwnMoves:0,observeFrames:1,movetimeMs:20},config)});
    driver.start();let result;
    for(let i=0;i<6;i++){now+=100;result=driver.tick();if(i===0)driver.assertTurn();}
    return {driver,result,taps};
}
assert.strictEqual(run(old,actual).taps,0,'raw current check blocks cached red-rook legal ticket');
for(const king of ['K','k']) {
    const missing=actual.slice();missing[missing.indexOf(king)]=null;
    const r=run(actual,missing);
    assert.strictEqual(r.taps,0,'a repaired board cannot resurrect missing '+king);
    assert.strictEqual(r.driver.machine.state(),'PAUSED');
    assert.strictEqual(r.result.stopRequired,true);
}
// Test actual panel cadence with a controlled scheduler, without waiting on wall time.
const lifecycle=load('app/panel/lifecycle.js');
let delay=0;const timer=global.setTimeout;global.setTimeout=(fn,ms)=>{delay=ms;return 1;};
const s={driver:{isThinking:()=>false,machine:{state:()=> 'WAIT_TURN'}},visionWorker:{},running:true};
lifecycle.create(s,{}).scheduleNextTick();assert.strictEqual(delay,120);
s.visionWorker=null;lifecycle.create(s,{}).scheduleNextTick();assert.strictEqual(delay,1400);
global.setTimeout=timer;
console.log('CHESS_ROYAL_SAFETY OK: K40 capture colors, check legality, both missing kings, fresh-frame votes, merge rejection and fast worker cadence');
