// @engine quickjs
"use strict";

/** K40 dev798：d7d2 同炮反吃断谱，以及对手不同应手中的车往返。 */
module.exports = {
    name: "K40 应手同炮反吃保留棋谱，已确认往返使用近均势候选",
    tags: ["capture-history", "win-seek", "driver", "replay"],
    run: function (t) {
        const fen = t.load("src/chess/fen.js");
        const legal = t.load("src/chess/legal-moves.js");
        const trackerModule = t.load("src/services/position-tracker.js");
        const winSeek = t.load("src/automation/win-seek.js");
        const key = trackerModule.cellsKey;
        const before = fen.parseFen("1r1akab2/2R6/3cb3n/3R2p1p/2p3n2/9/P1P1P1r1P/N2CB3C/9/2BAKA3 b - - 0 1").board;
        const after = legal.applyMove(before, { iccs: "d7d2" }).board;
        t.ok(legal.allLegalMoves(before,"b").some(function (m) {return m.iccs === "d7d2";}), "真实日志炮反吃合法");
        t.equal(legal.matchObserved(before, "b", after).status, "UNIQUE", "原始新盘只由 d7d2 解释");
        // 实际驱动先走红炮 d3d2 建立落点事实，再观察黑炮 d7d2。
        const initial = before.slice(); initial[57] = initial[66]; initial[66] = null;
        let live = initial, now = 10000, taps = 0;
        const notes = [];
        const grid = {originX:50,originY:200,stepX:100,stepY:90,version:1,orientation:"RED_BOTTOM"};
        const driver = t.load("src/automation/auto-driver.js").createDriver({
            allowClick:true, nowMono:function () { return now; }, sleep:function () {},
            log:function (text) { notes.push(text); }, locate:function () { return {ok:true,grid:grid}; },
            recognize:function () { return {ok:true,chars:live.slice(),rawChars:live.slice(),unknownCells:0,grid:grid,
                glyphStats:{circleEvidence:{active:true,sides:{66:live[66] === "c" ? "BLACK" : "RED"}}}}; },
            analyze:function () { return {ok:true,iccs:"d3d2",score:-9,depth:20,ms:10}; },
            tap:function (x,y,opt) { taps++; if (opt.kind === "to") live = before; return {ok:true}; },
            screen:function () { return {width:1080,height:2400}; },
            config:{computeMode:"inline",prethink:false,winSeek:false,bookOwnMoves:0,observeFrames:1,movetimeMs:20}
        });
        const until = function (condition) {
            for (let i=0;i<30 && !condition();i++) { now+=100; driver.tick(); }
            return condition();
        };
        driver.start(); driver.tick(); driver.assertTurn();
        t.ok(until(function () { return driver.ownMoveCount() === 1; }), "先确认红炮 d3d2");
        live = after;
        now+=100; driver.tick();
        t.equal(key(driver.lastReliable().board),key(before),"应手第一帧保留被吃红炮的前盘面");
        t.equal(driver.tracker().ledger.moves.join(" "),"d3d2","单帧不能提前收录对方着法");
        t.ok(until(function () { return key(driver.lastReliable().board) === key(after); }), "第二帧确认炮反吃");
        t.equal(driver.tracker().ledger.moves.join(" "),"d3d2 d7d2","同兵种反吃不能重开棋谱");
        t.equal(driver.stats().trackerRejects,0,"反吃没有账本拒收");
        t.ok(driver.stats().replyCaptureFactsExpired > 0,"走过旧事实作废但保留前基准路径");
        if (!driver.stats().replyCaptureFactsExpired) t.log(notes.join("\n"));
        t.notOk(notes.some(function (s) { return s.indexOf("借回修订前基准") >= 0; }),"无需破坏历史后借基准");
        t.equal(taps,2,"仅我方一次实际落子");
        driver.stop();

        // 非唯一/不合法的新盘不能借“吃子”跳过常规基准修订。
        const state = {cfg:{},opts:{},stats:{},nowMono:function () {return 10000;},
            machine:{mySide:function () {return "w";},state:function () {return "WAIT_OPPONENT";},
                versions:function () {return {sessionId:1};}},
            lastReliable:{board:before,side:"b"},
            knownFacts:t.load("src/automation/known-facts.js").createKnownFacts()};
        let repaired=0;
        const noisy=after.slice(); noisy[33]=null; // 无关黑卒凭空消失，无合法单步解释
        const actions={note:function () {},recognizeMerged:function () {
            return {ok:true,chars:noisy.slice(),rawChars:noisy.slice(),unknownCells:0,
                glyphStats:{circleEvidence:{active:true,sides:{66:"BLACK"}}}};
        },resyncReliableAt:function () {repaired++;},resetStuckStreak:function () {},clearPhantoms:function () {},
            countUnknownChars:function (b) {return b.filter(function (c) {return c === "?";}).length;}};
        state.knownFacts.replace([{index:66,char:"C"}]);
        t.load("src/automation/driver/observation.js").create(state,actions).observeOnce({});
        t.equal(repaired,1,"无唯一合法解释仍执行原有修订路径");

        // 记录必须由真实确认链生成；对方每步不同，所以整盘重复检测不会命中。
        let board=fen.parseFen(fen.START_FEN).board;
        const seen=winSeek.createSeenPositions();
        const shuttles=winSeek.createConfirmedShuttles();
        const ledger=trackerModule.createPositionTracker(); ledger.adopt({board:board,side:"w"});
        const hs={cfg:{},opts:{},stats:{trackerErrors:0,trackerRejects:0,trackerMismatch:0},
            ownMoveCount:0,positionTracker:ledger,lastReliable:{board:board,side:"w"},
            machine:{mySide:function () {return "w";}},winSeekShuttles:shuttles};
        const ha={note:function () {},noteWinSeekPosition:function (b,s) {seen.note(b,s);}};
        Object.assign(ha,t.load("src/automation/driver/history.js").create(hs,ha));
        let side="w", lastEntry=null;
        ["a0a1","a9a8","a1a0","a8a7","a0a1","b9c7"].forEach(function (iccs,index) {
            const next=legal.applyMove(board,{iccs:iccs}).board;
            hs.lastReliable={board:next,side:side === "w" ? "b" : "w"};
            lastEntry={iccs:iccs,fromBoard:board,side:side,afterBoard:next};
            if (index%2) ha.trackInferredMoves([{iccs:iccs}],board,side,"opponent");
            else ha.trackConfirm(lastEntry);
            board=next; side=hs.lastReliable.side;
        });
        const moves=legal.allLegalMoves(board,"w");
        const shuttle=function () {return shuttles.moves(moves,board).map(function (m) {return m.iccs;});};
        t.equal(winSeek.repeatMoves(moves,board,"w",seen,{minSeen:2}).length,0,"对手不同应手尚无整盘重复");
        t.equal(shuttle().join(" "),"a1a0","三次确认的同子往返提前生成下一次返回候选");
        ha.trackConfirm(lastEntry);
        t.equal(shuttle().join(" "),"a1a0","重复确认不会额外记一手或破坏链");
        const snapshot=JSON.stringify(ledger.snapshot());
        ha.trackConfirm({iccs:"a1h8",fromBoard:board,side:"w",afterBoard:board});
        t.equal(JSON.stringify(ledger.snapshot()),snapshot,"非法确认不收入账本");
        t.equal(shuttle().join(" "),"a1a0","非法确认不记入往返");
        const cfg=t.load("src/automation/driver-defaults.js");
        const candidates=[{move:"a1a0",scoreCp:-9,depth:25},{move:"a1a2",scoreCp:-14,depth:25}];
        const opts={bestMove:"a1a0",side:"w",minScore:cfg.winSeekScoreMin,maxScore:cfg.winSeekScoreMax,slack:20};
        t.equal(winSeek.preferAlternative(candidates,shuttle(),opts).iccs,"a1a2","默认微负评分仍比较安全换招");
        t.equal(winSeek.preferAlternative(candidates,shuttle(),Object.assign({},opts,{minScore:0})),null,"显式旧下限仍有效");
        t.equal(winSeek.preferAlternative([{move:"a1a0",scoreCp:-9,depth:25},
            {move:"a1a2",scoreCp:-200,depth:25}],shuttle(),opts),null,"不会为了避往返而亏棋");
        t.equal(winSeek.preferAlternative([{move:"a1a0",scoreCp:-100,depth:25},
            {move:"a1a2",scoreCp:-105,depth:25}],shuttle(),opts),null,"明显劣势保持引擎首选");
        t.equal(winSeek.preferAlternative([{move:"a1a0",scoreCp:9,depth:25},
            {move:"a1a2",scoreCp:14,depth:25}],shuttle(),Object.assign({},opts,{side:"b"})).iccs,"a1a2","黑方评分视角一致");
        const mpState=Object.assign(hs,{winSeekSeen:seen,cfg:{winSeek:true,winSeekMinSeen:2},
            stats:{winSeekHits:0},nowMono:function () {return 20000;},lastWinSeekNoteAt:0});
        const policy=t.load("src/automation/driver/move-policy.js").create(mpState,{note:function () {}});
        t.equal(policy.winSeekRepeatListFor(board,"w").join(" "),"a1a0","生产候选出口合并已确认往返");
        ha.trackFix(board,"w");
        t.equal(shuttle().length,0,"基准修订清空此前往返");
        const seedShuttle=function () {
            let b=fen.parseFen(fen.START_FEN).board;
            ["a0a1","a1a0","a0a1"].forEach(function (iccs) {
                shuttles.note(b,"w",iccs,"w"); b=legal.applyMove(b,{iccs:iccs}).board;
            });
        };
        seedShuttle();
        t.equal(shuttle().length,1,"吃子前循环确实存在");
        let captureBoard=board.slice(); captureBoard[0]="R"; captureBoard[9]="r";
        shuttles.note(captureBoard,"b","a8a9","w");
        t.equal(shuttle().length,0,"对方吃子不保留旧循环");
        seedShuttle(); ha.trackClear("new-session",true);
        t.equal(shuttle().length,0,"新会话/换边清空循环");
        seedShuttle(); ha.trackAdopt(board,"w");
        t.equal(shuttle().length,0,"没有着法语义的新基准清空循环");
        t.log("K40 d7d2 反吃回放、微负评分和不同应手中的往返保护通过");
    }
};
