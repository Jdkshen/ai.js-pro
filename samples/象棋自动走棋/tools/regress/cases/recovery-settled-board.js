// @engine quickjs
"use strict";

module.exports = {
    name: "K40 第129步确认超时：恢复吃士和象应手，保持完整棋谱",
    tags: ["recovery", "history", "capture", "replay"],
    run: function (t) {
        const fen=t.load("src/chess/fen.js"), legal=t.load("src/chess/legal-moves.js");
        const trackerModule=t.load("src/services/position-tracker.js");
        const history=t.load("src/automation/driver/history.js");
        const baseline=t.load("src/automation/driver/baseline.js");
        const noop=function(){};
        // 2026-10-05 dev-800 完整实战棋谱，末手 b6d7 绝杀。
        const moves=("h2e2 h9g7 h0g2 i9h9 c3c4 h7i7 b0c2 g6g5 i0i1 d9e8 i1f1 b9c7 a3a4 c9e7 b2a2 b7b3 e3e4 b3i3 a0b0 i3i5 f1f6 h9h1 f6g6 h1g1 c2e1 a9d9 g6g7 i5i0 b0b3 d9d1 b3f3 i6i5 g7h7 e9d9 a2a0 i5i4 f3f4 g5g4 g3g4 i4h4 e2c2 i0f0 f4f0 i7i0 h7h4 i0f0 e0f0 c7a8 c2c3 a8b6 c3g3 d1e1 g3g9 e7g9 g2e1 b6c4 a0a1 g1g3 h4h5 d9e9 h5d5 g9e7 a1a6 c6c5 d5d6 g3g4 c0e2 g4f4 f0e0 c4e3 d6e6 e9d9 e1c0 e3g2 d0e1 g2h4 a6d6 h4f3 d6d1 e7c9 e6b6 d9e9 b6b3 e8d9 b3e3 f3h4 a4a5 c9e7 c0d2 f4f3 e3f3 h4f3 d2b1 f3g5 b1c3 c5c4 e2c4 f9e8 e4e5 e8f7 c4e2 d9e8 e2g4 e7g9 c3d5 e9f9 d5b6 g5e4 d1d4 f9f8 e0f0 f8f9 b6c8 e8d9 e5e6 f9f8 e6e7 d9e8 d4d5 e4f6 d5f5 e8d7 e1f2 g9i7 f0e0 d7e8 c8b6 f8f9 e7e8 i7g9 e8e9 f9f8 b6d7").split(" ");
        const boards=[fen.parseFen(fen.START_FEN).board];
        const tracker=trackerModule.createPositionTracker();
        tracker.adopt({board:boards[0],side:"w"});
        for(let i=0;i<moves.length;i++) {
            const side=i%2===0?"w":"b";
            const move=legal.allLegalMoves(boards[i],side).filter(function(m){return m.iccs===moves[i];})[0];
            if(!move)throw new Error("Illegal fixture ply "+(i+1));
            boards.push(legal.applyMove(boards[i],move).board);
            if(i<128) {
                const result=tracker.confirmMove({iccs:moves[i],fromBoard:boards[i],side:side,afterBoard:boards[i+1]});
                if(!result.ok)throw new Error("Seed history rejected at "+(i+1));
            }
        }
        const notes=[];
        const state={opts:{},cfg:{},stats:{baselineRebuilds:0,trackerErrors:0,trackerMismatch:0,trackerRejects:0},
            ownMoveCount:64,lastReliable:{board:boards[128].slice(),side:"w"},
            positionTracker:tracker,knownFacts:t.load("src/automation/known-facts.js").createKnownFacts(),
            frameConsensus:{clear:noop},crossFrameGate:{reset:noop},stallFilledUntil:{},
            nowMono:function(){return 10000;},machine:{mySide:function(){return "w";},config:{}}};
        const actions={note:function(s){notes.push(s);},noteWinSeekPosition:noop};
        Object.assign(actions,history.create(state,actions));
        Object.assign(actions,baseline.create(state,actions));
        t.equal(tracker.snapshot().historyStatus,"complete","前128步为完整历史");
        // 实战重建观测：象已从 i7 到 g9，但旧起点残影/补齐仍留在 i7。
        const noisy=boards[130].slice();noisy[26]="b";
        t.ok(actions.rebuildBaselineFrom(noisy),"确认超时后的盘面可恢复");
        const key=trackerModule.cellsKey;
        t.equal(key(noisy),key(boards[130]),"重建结果采用推导出的完整盘面，包括清空象起点");
        t.equal(noisy[26],null,"旧 i7 象不能被带入新基准");
        t.equal(key(state.lastReliable.board),key(boards[130]),"可靠基准与合法两步重放一致");
        t.equal(tracker.snapshot().historyStatus,"complete","恢复合法两步不能降为部分历史");
        t.equal(tracker.snapshot().ledger.ply,130,"补入吃士和象应手两步");
        t.equal(tracker.snapshot().positionCommand,"position fen "+fen.START_FEN+" moves "+moves.slice(0,130).join(" "),"引擎得到恢复前后完整棋谱");
        t.equal(state.ownMoveCount,65,"只补记一手我方着法");
        t.equal(state.stats.trackerMismatch,0,"盘面和账本一致");
        t.equal(state.stats.trackerErrors,0,"恢复无账本异常");
        const unchanged=tracker.snapshot().positionCommand;
        actions.rebuildBaselineFrom(boards[130].slice());
        t.equal(tracker.snapshot().positionCommand,unchanged,"重复恢复不重复记账");
        t.equal(state.ownMoveCount,65,"重复恢复不重复计走子数");
        for(let i=130;i<133;i++) {
            const side=i%2===0?"w":"b";
            state.lastReliable={board:boards[i+1].slice(),side:side==="w"?"b":"w"};
            actions.trackConfirm({iccs:moves[i],fromBoard:boards[i],side:side,afterBoard:boards[i+1]});
        }
        t.equal(tracker.snapshot().historyStatus,"complete","收尾到第133步仍为完整历史");
        t.equal(tracker.snapshot().ledger.ply,133,"后续三步连续入账");
        t.equal(legal.allLegalMoves(boards[133],"b").length,0,"回放终局黑方无合法应对");
        t.equal(state.stats.trackerRejects,0,"收尾不拒收合法着法");
        if(t.failures.length)t.log(notes.slice(-10).join(" | "));
    }
};
