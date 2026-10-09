// @engine quickjs
"use strict";

module.exports={
    name:"K40 无攻子残局停止自动求胜，保留最后大子与唯一应对",
    tags:["endgame","driver","win-seek","replay"],
    run:function (t) {
        const f=t.load("src/chess/fen.js"),l=t.load("src/chess/legal-moves.js");
        const policy=t.load("src/automation/endgame-policy.js"),win=t.load("src/automation/win-seek.js");
        const dead=f.parseFen("4k4/9/5a3/6c2/6b2/2B6/9/5A2B/9/3A1K3 w - - 0 1").board;
        const guard=policy.createGuard();
        const input={rawBoard:dead,board:dead,referenceBoard:dead,side:"w",sessionId:1,observationId:1,frameId:7};
        t.equal(policy.inspect(dead,"w").attackers,0,"K40 最后兵被吃后的真实盘面无攻子");
        t.equal(policy.inspect(dead,"b").attackers,1,"对方仍有一炮，不能把游戏直接判和");
        t.notOk(guard.check(input).confirmed,"一张新帧只暂缓自动求胜");
        t.notOk(guard.check(Object.assign({},input,{observationId:2})).confirmed,"同一缓存帧不能算两票");
        t.ok(guard.check(Object.assign({},input,{frameId:8})).confirmed,"第二张新帧确认停止");
        t.notOk(guard.check(Object.assign({},input,{sessionId:2,frameId:9})).confirmed,"换局清空旧票");
        const withPawn=dead.slice();withPawn[4]="P";withPawn[13]="k";
        t.equal(guard.check(Object.assign({},input,{referenceBoard:withPawn})),null,"可信账本还有兵不能因漏检停止");
        const unknown=dead.slice();unknown[4]="?";
        t.equal(guard.check(Object.assign({},input,{rawBoard:unknown})),null,"未知格不是无攻子证据");
        t.equal(guard.check(Object.assign({},input,{rawBoard:withPawn})),null,"原始图像仍有兵时不能靠修补删兵停止");
        const badKing=dead.slice();badKing[4]=null;
        t.equal(policy.inspect(badKing,"w"),null,"缺将帅交给将帅安全流程，不当无攻子");
        const mirrored=dead.slice().reverse().map(function (p) {
            return !p?p:p===p.toUpperCase()?p.toLowerCase():p.toUpperCase();
        });
        t.equal(policy.inspect(mirrored,"b").attackers,0,"黑方自身子力口径一致");

        function run(config) {
            let frame=0,now=10000,searches=0,taps=0;
            const grid={originX:50,originY:200,stepX:100,stepY:90,version:1,orientation:"RED_BOTTOM"};
            const legalMove=l.allLegalMoves(dead,"w")[0].iccs;
            const driver=t.load("src/automation/auto-driver.js").createDriver({
                allowClick:true,nowMono:function () {return now;},sleep:function () {},log:function () {},
                locate:function () {return {ok:true,grid:grid};},
                recognize:function () {return {ok:true,chars:dead.slice(),rawChars:dead.slice(),unknownCells:0,
                    grid:grid,frame:{frameId:++frame}};},
                analyze:function () {searches++;return {ok:true,iccs:legalMove,score:0,depth:20,ms:10};},
                tap:function () {taps++;return {ok:true};},screen:function () {return {width:1080,height:2400};},
                config:Object.assign({computeMode:"inline",prethink:false,winSeek:true,bookOwnMoves:0,
                    observeFrames:1,movetimeMs:20},config||{})
            });
            driver.start();driver.tick();driver.assertTurn();
            now+=100;const first=driver.tick();
            now+=100;const second=driver.tick();
            const result={first:first,second:second,searches:searches,taps:taps,state:driver.machine.state()};
            driver.stop();return result;
        }
        const stopped=run();
        t.notOk(stopped.first.stopRequired,"第一轮不立即停，留一次新帧复核");
        t.ok(stopped.second.stopRequired,"生产驱动请求宿主停止循环");
        t.equal(stopped.state,"PAUSED","停止前使所有旧任务失效");
        t.equal(stopped.searches,0,"无攻子复核期间不继续烧搜索");
        t.equal(stopped.taps,0,"无攻子复核期间不点击士相");
        t.ok(run({stopWithoutAttackers:false}).searches>0,"显式选择继续保和仍可搜索");
        t.ok(run({winSeek:false}).searches>0,"关闭求胜不强制停止");

        const trade=f.parseFen("3r1k3/9/9/9/9/9/8P/9/9/R3K4 w - - 0 1").board;
        const quiet=win.quietTradeMoves(l.allLegalMoves(trade,"w"),trade,"w",{}).map(function (m) {return m.iccs;});
        t.ok(quiet.indexOf("a0d0")>=0,"还有兵也保护最后一枚车的静着兑子");
        const candidates=[{move:"a0d0",scoreCp:0,depth:20},{move:"a0a1",scoreCp:-5,depth:20}];
        t.equal(win.preferAlternative(candidates,quiet,{bestMove:"a0d0",side:"w",slack:20}).iccs,
            "a0a1","评分接近时选择保留车的着法");
        t.equal(win.preferAlternative([{move:"a0d0",scoreCp:0,depth:20},{move:"a0a1",scoreCp:-100,depth:20}],
            quiet,{bestMove:"a0d0",side:"w",slack:20}),null,"不为保留最后车而亏棋");
        // 真实 K40 11:00:31：被车将军后只有 f5f0 合法，不能强行禁止必需兑子。
        const forced=f.parseFen("5P3/4k4/3ab3P/9/4cR3/9/9/4B4/4A4/2BAK2r1 w - - 0 1").board;
        t.equal(l.allLegalMoves(forced,"w").map(function (m) {return m.iccs;}).join(" "),"f5f0","单候选来自唯一应对");
        t.equal(win.preferAlternative([{move:"f5f0",scoreCp:0,depth:29}],["f5f0"],
            {bestMove:"f5f0",side:"w",slack:20}),null,"唯一应对不能为避重复/避兑强行换招");
        t.log("K40 末兵被吃后的停止门槛、保留最后车和强制应将回放通过");
    }
};
