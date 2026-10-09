// @engine quickjs
"use strict";

module.exports = {
    name: "重复局面独立核对：新手机车炮往返与旧基准漂移",
    tags: ["driver", "replay", "win-seek", "repeat-audit"],
    run: function (t) {
        const fen = t.load("src/chess/fen.js");
        const legal = t.load("src/chess/legal-moves.js");
        const geometry = t.load("src/vision/geometry.js");
        const driverModule = t.load("src/automation/auto-driver.js");
        const base = "2b1ka3/4a4/6r2/9/p8/2P3B1p/P7P/2N2R3/2r1A3c/4KA1R1 w - - 0 1";
        const grid = geometry.createGrid({originX:96,originY:800,stepX:136,stepY:136,
            orientation:geometry.ORIENTATION_RED_BOTTOM,version:1});
        function scenario(mode) {
            let board = fen.parseFen(base).board, side = "w", own = 0, calls = 0, audits = 0;
            const notes = [], auditRequests = [];
            let lastOwn = null;
            const driver = driverModule.createDriver({
                allowClick:true, log:function (s) { notes.push(s); }, sleep:function () {},
                screen:function () { return {width:1280,height:2772}; },
                locate:function () { return {ok:true,grid:grid}; },
                recognize:function (request) {
                    calls++;
                    let chars = board.slice();
                    if (request.freshRead && request.referenceBoard === null) {
                        audits++; auditRequests.push(request);
                        if (mode === "second-failed" && audits % 2 === 0) return {ok:false,reason:"第二帧截图失败"};
                        if (mode === "drift") { chars[53] = "P"; chars[62] = null; }
                        if (mode === "unknown") chars[53] = "?";
                        if (mode === "conflict" && audits % 2 === 0) chars[53] = "P";
                    }
                    return {ok:true,chars:chars,rawChars:chars.slice(),unknownCells:0,grid:grid};
                },
                analyze:function (position) {
                    const moves = legal.allLegalMoves(position.board, position.side);
                    const iccs = moves.some(function (m) {return m.iccs === "h0h1";}) ? "h0h1" : "h1h0";
                    lastOwn = iccs;
                    return {ok:true,iccs:iccs,move:iccs,score:10,depth:16,ms:1,pvIccs:[iccs],
                        candidates:[{move:iccs,scoreCp:10,depth:16,pv:iccs}]};
                },
                tap:function (x,y,options) {
                    if (options && options.kind === "to") {
                        board = legal.applyMove(board,{iccs:lastOwn}).board;
                        side = "b"; own++;
                    }
                    return {ok:true,method:"fixture"};
                },
                config:{computeMode:"inline",prethink:false,predictReply:false,observeFrames:1,
                    observeGapMs:1,observeFramesByState:true,bookOwnMoves:0,winSeek:true,
                    repeatBoardAudit:mode !== "disabled",autoDeclareTurn:false}
            });
            driver.start(); driver.tick(); driver.assertTurn();
            for (let i=0;i<140;i++) {
                if (driver.machine.state() === "PAUSED" || own >= 8) break;
                if (side === "b" && driver.lastReliable() && driver.lastReliable().side === "b") {
                    board = legal.applyMove(board,{iccs:board[80] === "c" ? "i1i0" : "i0i1"}).board;
                    side = "w";
                }
                driver.tick();
            }
            const paused = driver.machine.state() === "PAUSED", beforeCalls = calls;
            const ledger = JSON.stringify(driver.tracker());
            if (paused) {
                board[53] = null;
                for (let i=0;i<4;i++) driver.tick();
                t.equal(calls,beforeCalls,mode+"：暂停后不再采集或重建基准");
                t.equal(JSON.stringify(driver.tracker()),ledger,mode+"：暂停后保留取证棋谱");
            }
            driver.stop();
            return {own:own,paused:paused,notes:notes,audits:audits,requests:auditRequests};
        }
        const drift = scenario("drift");
        t.ok(drift.paused,"旧基准与新帧不符时必须暂停");
        t.ok(drift.own <= 6,"三个确认的往返轮次内拦截，不继续第 4 轮");
        t.equal(drift.audits,2,"独立采集两张帧，不沿用 WAIT_TURN 单帧配置");
        t.ok(drift.notes.some(function (s) {return s.indexOf("(8,5)p→P") >= 0 && s.indexOf("(8,6)P→空") >= 0;}),
            "日志明确给出颜色与多余棋子的两格差异");
        const same = scenario("same");
        t.ok(!same.paused && same.own >= 8,"两张新帧一致时保留合法应对，不能强制走输棋候选");
        t.equal(same.audits,4,"两个我方重复盘面各复核一次，复核通过后复用结果");
        const unknown = scenario("unknown");
        t.ok(unknown.paused,"重读仍未知时不借旧事实补齐再通过");
        const conflict = scenario("conflict");
        t.ok(conflict.paused,"两张帧颜色矛盾时不放行");
        const failed = scenario("second-failed");
        t.ok(failed.paused,"第二帧失败不能用第一帧冒充两帧核对");
        const disabled = scenario("disabled");
        t.ok(!disabled.paused && disabled.own >= 8 && disabled.audits === 0,"显式关闭保持旧行为");
    }
};
