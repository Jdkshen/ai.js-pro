// @engine quickjs
"use strict";

module.exports = {
    name: "两局真机棋谱：保留保和候选，求胜分差保护，同步与 worker 一致",
    tags: ["driver", "replay", "win-seek", "loss-policy"],
    run: function (t) {
        // QuickJS require treats JSON as JavaScript; read and parse the fixture explicitly.
        const fixtures = t.env === "node"
            ? t.load("tools/regress/fixtures/loss-games-20260927.json")
            : JSON.parse(files.read(__dirname + "/../fixtures/loss-games-20260927.json"));
        t.ok(Array.isArray(fixtures) && fixtures.length === 2, "两局棋谱必须实际载入，不能空跑");
        if (!Array.isArray(fixtures) || fixtures.length !== 2) return;
        const fen = t.load("src/chess/fen.js");
        const legal = t.load("src/chess/legal-moves.js");
        const win = t.load("src/automation/win-seek.js");
        const loop = t.load("src/automation/loop-history.js");
        const defaults = t.load("src/automation/driver-defaults.js");
        const policyModule = t.load("src/automation/driver/move-policy.js");
        const searchModule = t.load("src/automation/driver/search.js");
        const candidates = [
            [{move:"g7g8",scoreCp:10,depth:20,pv:"g7g8"},
                {move:"g7g4",scoreCp:10,depth:20,pv:"g7g4"},
                {move:"g7d7",scoreCp:-201,depth:20,pv:"g7d7 c5b3"}],
            [{move:"c5c9",scoreCp:10,depth:20,pv:"c5c9"},
                {move:"c5e5",scoreCp:-142,depth:20,pv:"c5e5 a9c9"}]
        ];

        for (let fi=0; fi<fixtures.length; fi++) {
            for (let worker=0; worker<2; worker++) {
                const fixture = fixtures[fi];
                let outcome = null, submitted = null, busy = false;
                const result = {ok:true,iccs:candidates[fi][0].move,score:10,depth:20,ms:1000,
                    pvIccs:[candidates[fi][0].move],candidates:candidates[fi],engine:"pikafish",
                    historyUsed:true,historyStatus:"complete"};
                const cfg = Object.assign({}, defaults.DEFAULTS || defaults, {
                    winSeek:true, winSeekMinSeen:2, winSeekScoreMin:0, winSeekScoreMax:25,
                    winSeekSwapSlack:20,prethink:true,predictReply:false,searchDepth:0,bookOwnMoves:0
                });
                const state = {cfg:cfg,stats:{observations:0,thinkInline:0,thinkOffThread:0},
                    planHistory:loop.createLoopHistory(),winSeekSeen:win.createSeenPositions(),
                    rejectedMoves:new Map(),decisionEpoch:0,lastThinkScore:10,nowMono:function(){return 20000;},
                    lastWinSeekNoteAt:0,ownMoveCount:fixture.moves.length/2,
                    machine:{versions:function(){return {sessionId:1};},state:function(){return "THINKING";},
                        currentJob:function(){return {jobId:1};},dispatch:function(event){outcome=event;return event;}},
                    prethink:{poll:function(){},busy:function(){return false;},take:function(){return false;}},
                    opts:{analyze:function(position,options){submitted=options;return Object.assign({},result);}},
                    workerSearch:{start:function(position,options){submitted=options;busy=true;return true;},
                        isRunning:function(){return busy;},poll:function(){busy=false;return {status:"done",result:result};}}
                };
                const actions={note:function(){},pump:function(event){return event;},
                    observeOnce:function(){return {ok:true,chars:board.slice(),unknownCells:0,conflicts:0};},
                    useWorkerSearch:function(){return !!worker;},isMateClaim:function(){return false;},
                    verifiedHistoryFor:function(){return {historyStatus:"complete",
                        positionCommand:"position startpos moves "+fixture.moves.join(" ")};}};
                const policy=policyModule.create(state,actions);
                Object.assign(actions,policy);
                let board=fen.parseFen(fen.START_FEN).board,side="w";
                policy.noteWinSeekPosition(board,side);
                for (let i=0;i<fixture.moves.length;i++) {
                    const move=legal.allLegalMoves(board,side).filter(function(m){return m.iccs===fixture.moves[i];})[0];
                    t.ok(!!move,fixture.name+" 第 "+(i+1)+" 半回合合法");
                    if (!move) return;
                    if (side==="w") {state.decisionEpoch=i;policy.recordPlanDecision(board,side,move.iccs);}
                    board=legal.applyMove(board,move).board;side=side==="w"?"b":"w";
                    policy.noteWinSeekPosition(board,side);
                }
                t.equal(fen.buildFen(board,side),fixture.fen,"实际棋谱回放终点一致");
                t.equal(policy.excludedIccsFor(board,side),null,"不能把保和候选变成硬禁手");
                const search=searchModule.create(state,actions);
                search.compute({jobId:1,board:board,side:side,movetimeMs:1000});
                if (worker) search.pollCompute();
                t.equal(outcome && outcome.iccs,result.iccs,"第 "+(fi+1)+" 局，worker="+worker+" 保留引擎好棋");
                t.equal(outcome && outcome.score,10,"不能接受明显劣势替代");
                t.equal(submitted.positionCommand,"position startpos moves "+fixture.moves.join(" "),"完整历史透传");
                t.equal(submitted.excludeIccs,null,"求胜开着也不硬排除");
                t.ok(submitted.multiPv>=2,"求胜有重复候选时比较多路");
                policy.markMoveRejected({baseFen:fen.buildFen(board,side),baseSide:side,moveICCS:result.iccs},"fixture rejected");
                t.ok(policy.excludedIccsFor(board,side).indexOf(result.iccs)>=0,"游戏实际拒绝仍有效");
            }
        }

        const options={bestMove:"a0a1",side:"w",minScore:0,maxScore:25,slack:20};
        const pair=function(a,b){return [{move:"a0a1",scoreCp:a,depth:20},{move:"a0a2",scoreCp:b,depth:20}];};
        t.equal(win.preferAlternative(pair(10,-194),["a0a1"],options),null,"禁止用大幅失分求变");
        t.equal(win.preferAlternative(pair(10,null),["a0a1"],options),null,"缺失分不能视为零分");
        t.equal(win.preferAlternative(pair(null,0),["a0a1"],options),null,"首选缺失分也不换招");
        t.equal(win.preferAlternative(pair(-10,-15),["a0a1"],options),null,"本次落后，即使上次均势也不求变");
        const mate=pair(10,5);mate[0].mate=3;
        t.equal(win.preferAlternative(mate,["a0a1"],options),null,"杀棋不参与软偏好");
        const altMate=pair(10,5);altMate[1].mate=-3;
        t.equal(win.preferAlternative(altMate,["a0a1"],options),null,"不能选择带被杀标记的替代");
        t.equal(win.preferAlternative(pair(10,5),["a0a1"],Object.assign({},options,{excludes:["a0a2"]})),null,"软偏好不复活真实禁手");
        const shallow=pair(10,5);shallow[1].depth=19;
        t.equal(win.preferAlternative(shallow,["a0a1"],options),null,"浅层候选不能冒充同层分差");
        t.equal(win.preferAlternative(pair(10,5),["a0a1"],options).iccs,"a0a2","可靠且接近时可求变");
        const black=Object.assign({},options,{side:"b"});
        t.equal(win.preferAlternative(pair(-10,194),["a0a1"],black),null,"黑方不能按红分排序而送棋");
        t.equal(win.preferAlternative(pair(-10,-5),["a0a1"],black).gap,5,"黑方同样正确比较分差");

        // 通过真实 finishCompute 检查换招后的评分、PV 和预思考种子一致。
        let event;
        const board=fen.parseFen(fen.START_FEN).board;
        const state={cfg:{prethink:true,predictReply:false,winSeekSwapSlack:20,winSeekScoreMin:0,winSeekScoreMax:25},
            stats:{},nowMono:function(){return 0;},machine:{dispatch:function(e){event=e;return e;}}};
        const actions={note:function(){},pump:function(e){return e;},isMateClaim:function(){return false;},recordPlanDecision:function(){}};
        searchModule.create(state,actions).finishCompute({jobId:1,board:board,side:"w"},{ok:true,iccs:"a0a1",score:10,
            depth:20,engine:"pikafish",pvIccs:["a0a1","a9a8"],repeatList:["a0a1"],
            candidates:[{move:"a0a1",scoreCp:10,depth:20},{move:"a0a2",scoreCp:5,depth:20,pv:"a0a2 b9c7"}]});
        t.equal(event.iccs,"a0a2","驱动应用软偏好");
        t.equal(event.score,5,"换招后评分同步");
        t.equal(state.lastThinkScore,5,"下次复核使用真实所选分");
        t.equal(state.prethinkSeed.reply,"b9c7","预思考使用新着法的主变");
    }
};
