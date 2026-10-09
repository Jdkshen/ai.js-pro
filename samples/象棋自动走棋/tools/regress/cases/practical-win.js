// @engine quickjs
"use strict";

module.exports = {
    name: "小米 8 真局求胜：提前保兵，同层分差保护，worker 与同步一致",
    tags: ["driver", "win-seek", "practical-win"],
    run: function (t) {
        const fixtures = t.env === "node" ? t.load("tools/regress/fixtures/practical-win-mi8-20261005.json") :
            JSON.parse(files.read(__dirname + "/../fixtures/practical-win-mi8-20261005.json"));
        const fen = t.load("src/chess/fen.js"), policy = t.load("src/automation/line-material.js");
        const search = t.load("src/automation/driver/search.js"), defaults = t.load("src/automation/driver-defaults.js");
        t.equal(fixtures.length, 3, "实局三个转折点不可空跑");
        const f = fixtures[1], board = fen.parseFen(f.position).board;
        const options = {enabled:true,bestMove:f.candidates[0].move,slack:20,minScore:-25,maxScore:80};
        const copy = function () { return JSON.parse(JSON.stringify(f.candidates.slice(0,2))); };
        const before = JSON.stringify(board);
        const chosen = policy.preferCandidate(board,"w",f.candidates,options);
        t.equal(chosen && chosen.iccs,"b9b4","第 69 半回合不再忽略仅低 3cp 的保兵走法");
        t.equal(chosen && chosen.gap,3,"真实分差 3cp");
        t.equal(chosen && chosen.headMaterial.pawns,1,"首选主变损失一兵");
        t.equal(chosen && chosen.material.pawns,2,"替代主变仍保有两兵");
        t.equal(chosen && chosen.material.value,chosen && chosen.headMaterial.value,"不能用丢车马炮换保兵");
        t.equal(JSON.stringify(board),before,"预测主变不能修改输入盘面或确认棋谱");
        t.equal(policy.preferCandidate(board,"w",f.candidates,Object.assign({},options,{enabled:false})),null,"关闭偏好不干预");
        t.equal(policy.shouldCompare(fen.parseFen(fen.START_FEN).board,"w",true),false,"完整开局不额外开多路");
        const noPawns=board.map(function(p){return p === "P" ? null : p;});
        t.equal(policy.shouldCompare(noPawns,"w",true),false,"已无兵时不会宣称保兵机会");
        for (let i=0;i<fixtures.length;i+=2) {
            const other=fixtures[i];
            t.equal(policy.preferCandidate(fen.parseFen(other.position).board,"w",other.candidates,
                Object.assign({},options,{bestMove:other.candidates[0].move})),null,
                "第 "+other.ply+" 半回合：没有可靠保兵候选就保留首选");
        }
        function rejected(change,message,extra) {
            const list=copy();change(list);
            t.equal(policy.preferCandidate(board,"w",list,Object.assign({},options,extra||{})),null,message);
        }
        rejected(function(c){c[1].scoreCp=-21;},"保兵分差超过 20cp 不换招");
        rejected(function(c){c[1].depth--;},"主变保兵也不能使用更浅候选");
        rejected(function(c){c[0].depth=15;},"浅层首选不拿主变作为保兵证据");
        rejected(function(c){c[1].mate=-3;},"不能选择被杀候选");
        rejected(function(c){c[0].mate=3;},"不能放弃引擎杀棋");
        rejected(function(c){c[1].scoreCp=null;},"缺失分不能当作零分");
        rejected(function(c){c[0].scoreCp=120;c[1].scoreCp=117;},"已明显占优时沿用引擎首选");
        rejected(function(c){c[0].scoreCp=-40;c[1].scoreCp=-43;},"明显落后不为保兵让分");
        rejected(function(c){c[1].pv=c[1].pv.split(" ").slice(0,11).join(" ");},"不同长度短主变不比较");
        rejected(function(c){c[1].pv=c[1].pv.replace("d7d4","a0a9");},"非法主变不能作为证据");
        rejected(function(c){c[0].pv="b9g9";},"首选短主变不能冒充完整观察");
        rejected(function(){},"不复活游戏实际拒绝的着法",{excludes:["b9b4"]});
        const mirror = function(iccs){return [0,2].map(function(k){return String.fromCharCode(105-(iccs.charCodeAt(k)-97))+String(9-Number(iccs.charAt(k+1)));}).join("");};
        const blackBoard=board.slice().reverse().map(function(p){return !p?p:p===p.toUpperCase()?p.toLowerCase():p.toUpperCase();});
        const black=copy().map(function(c){c.move=mirror(c.move);c.pv=c.pv.split(" ").map(mirror).join(" ");c.scoreCp=-c.scoreCp;return c;});
        const blackChoice=policy.preferCandidate(blackBoard,"b",black,Object.assign({},options,{bestMove:black[0].move}));
        t.equal(blackChoice && blackChoice.iccs,mirror("b9b4"),"黑方正确翻转评分并检查合法主变");

        const newPhone=t.env === "node" ? t.load("tools/regress/fixtures/practical-win-newphone-20261005.json") :
            JSON.parse(files.read(__dirname + "/../fixtures/practical-win-newphone-20261005.json"));
        const newBoard=fen.parseFen(newPhone.position).board;
        const newOptions=Object.assign({},options,{bestMove:newPhone.played});
        const newChoice=policy.preferCandidate(newBoard,"w",newPhone.candidates,newOptions);
        t.ok(policy.shouldCompare(newBoard,"w",true),"新手机第 35 回合，最后一马尚在时提前开多路");
        t.equal(newChoice && newChoice.iccs,"e5e6","新手机不先弃掉最后一马再进入炮往返");
        t.equal(newChoice && newChoice.gap,13,"保留进攻子力只让 13cp，仍在统一分差上限内");
        t.equal(newChoice && newChoice.headMaterial.majors,3,"原主变剩两车一炮");
        t.equal(newChoice && newChoice.material.majors,4,"替代主变仍保有两车一炮一马");
        t.equal(newChoice && newChoice.material.pawns,newChoice && newChoice.headMaterial.pawns,"不以更少兵卒换保马");

        const native=t.env === "node" ? t.load("tools/regress/fixtures/practical-win-k40-pv-20261005.json") :
            JSON.parse(files.read(__dirname + "/../fixtures/practical-win-k40-pv-20261005.json"));
        const forecast=policy.forecastRepeat(newBoard,"w",native.candidates,native.head,true);
        t.ok(forecast && forecast.avoid.indexOf("e5f5")>=0,"K40 实际 9 步主变提前读出将来的炮往返");
        t.ok(forecast && forecast.avoid.indexOf("e5e6")>=0,"相近候选的另一段主变重复同样排除");
        t.ok(forecast && forecast.avoid.indexOf("e0d0")>=0,"只有一手 PV 不能冒充已验证非重复候选");
        t.ok(forecast && forecast.avoid.indexOf("c3d5")<0,"同深度马进攻线在检查范围内无重复");
        t.equal(policy.forecastRepeat(newBoard,"w",native.candidates,native.head,false),null,"关闭求胜不检查预测往返");
        const invalid=JSON.parse(JSON.stringify(native.candidates));invalid[0].pv=invalid[0].pv.replace("g6c6","a0a9");
        t.equal(policy.forecastRepeat(newBoard,"w",invalid,native.head,true),null,"非法主变不能触发预测避重");
        const win=t.load("src/automation/win-seek.js");
        const early=win.preferAlternative(native.candidates,forecast.avoid,Object.assign({},options,{bestMove:native.head,side:"w"}));
        t.equal(early && early.iccs,"c3d5","真机候选在实际往返前有安全马进攻替代");
        t.equal(early && early.gap,20,"提前避重仍在原统一 20cp 预算内");

        for(let worker=0;worker<2;worker++) for(let enabled=0;enabled<2;enabled++) {
            let event,submitted,busy=false;
            const result={ok:true,engine:"pikafish",iccs:f.candidates[0].move,score:0,depth:24,ms:1800,
                pvIccs:f.candidates[0].pv.split(" "),candidates:f.candidates,historyUsed:true,historyStatus:"complete"};
            const state={cfg:Object.assign({},defaults.DEFAULTS||defaults,{winSeek:!!enabled,prethink:true,predictReply:false}),
                stats:{observations:0,thinkInline:0,thinkOffThread:0},noMoveStreak:0,ownMoveCount:34,
                nowMono:function(){return 0;},prethink:{poll:function(){},busy:function(){return false;},take:function(){return false;}},
                machine:{state:function(){return "THINKING";},currentJob:function(){return {jobId:1};},
                    versions:function(){return {sessionId:1};},dispatch:function(e){event=e;return e;}},
                winSeekSeen:{count:function(){return 0;}},
                opts:{analyze:function(p,o){submitted=o;return result;}},
                workerSearch:{start:function(p,o){submitted=o;busy=true;return true;},isRunning:function(){return busy;},
                    poll:function(){busy=false;return {status:"done",result:result};}}};
            const actions={note:function(){},pump:function(e){return e;},isMateClaim:function(){return false;},
                recordPlanDecision:function(){},useWorkerSearch:function(){return !!worker;},excludedIccsFor:function(){return null;},
                winSeekSwapListFor:function(){return [];},winSeekRepeatListFor:function(){return [];},
                verifiedHistoryFor:function(){return {positionCommand:f.positionCommand,historyStatus:"complete"};}};
            const driver=search.create(state,actions);
            driver.compute({jobId:1,board:board,side:"w",movetimeMs:3000});
            if(worker) driver.pollCompute();
            t.equal(submitted.multiPv,enabled?5:0,"没有现成重复/兑子名单也提前比较，worker="+worker+" enabled="+enabled);
            t.equal(event && event.iccs,enabled?"b9b4":"b9g9","驱动实际应用策略，worker="+worker+" enabled="+enabled);
            t.equal(event && event.score,enabled?-3:0,"所选候选评分同步");
            t.equal(state.prethinkSeed && state.prethinkSeed.reply,enabled?"d7d4":"e7g9","预思考跟随所选主变");
            t.equal(submitted.positionCommand,f.positionCommand,"完整实局历史保留");
            t.equal(submitted.maxTotalMs,3000,"保兵不额外增加搜索总时间");
        }
        let earlyEvent;
        const earlyState={cfg:Object.assign({},defaults.DEFAULTS||defaults,{winSeek:true,prethink:true,predictReply:false}),
            stats:{},nowMono:function(){return 0;},machine:{dispatch:function(e){earlyEvent=e;return e;}}};
        const earlyActions={note:function(){},pump:function(e){return e;},isMateClaim:function(){return false;},recordPlanDecision:function(){}};
        search.create(earlyState,earlyActions).finishCompute({jobId:1,board:newBoard,side:"w"},
            {ok:true,engine:"pikafish",iccs:native.head,score:native.score,depth:native.depth,ms:native.engineMs,
                candidates:native.candidates,pvIccs:native.candidates[0].pv.split(" ")});
        t.equal(earlyEvent && earlyEvent.iccs,"c3d5","驱动应用真机短 PV 提前避重");
        t.equal(earlyEvent && earlyEvent.score,-10,"实际所选候选分跟随，不伪报原头名分");
        t.equal(earlyState.prethinkSeed && earlyState.prethinkSeed.reply,"c0c5","预测种子跟随马进攻线");
    }
};
