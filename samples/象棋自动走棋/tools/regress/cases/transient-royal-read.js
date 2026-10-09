// @engine quickjs
"use strict";

module.exports = {
    name:"新手机将帅短暂漏读：独立复核与暂停日志",
    tags:["driver", "royal", "replay", "panel"],
    run:function(t) {
        const safety=t.load("src/automation/observation-safety.js");
        const fen=t.load("src/chess/fen.js");
        const recognition=t.load("src/automation/driver/recognition.js");
        const photo="6c2/6c2/5k3/8N/3N5/9/9/3K4B/4A4/3A5 w - - 0 1";
        const board=fen.parseFen(photo).board;
        const missed=board.slice(); missed[23]=null;
        function frame(chars,id) {return {ok:true,chars:chars.slice(),rawChars:chars.slice(),unknownCells:0,
            frame:{window:{frameId:id}},layout:fen.layoutOf(chars,{allowUnknown:true})};}
        const bad=frame(missed,10), original=JSON.stringify(bad), requests=[];
        let waits=0, reads=0;
        function read(request) {requests.push(request);reads++;return frame(board,10+reads);}
        const action=recognition.create({cfg:{observeFrames:1,observeGapMs:1},stats:{},
            clock:function(){},opts:{recognize:read}},{note:function(){}});
        const checked=safety.recheckRoyal(bad,{asyncObservation:true,frames:1,referenceBoard:board},
            action.recognizeMerged,function(){waits++;});
        t.ok(checked.recovered,"短暂漏读后可用独立新帧恢复，不能永久暂停");
        t.equal(reads,2,"等待轮次的单帧配置不能替代两帧复核");
        t.equal(waits,1,"每次异常只加一次动画等待");
        requests.forEach(function(request) {
            t.equal(request.referenceBoard,null,"不借可靠基准补回黑将");
            t.ok(request.freshRead && request.independentRead,"跳过差分缓存与历史补丁");
            t.equal(request.asyncObservation,false,"复核用同步新读数，不能把 pending 当证据");
        });
        t.deepEqual(checked.result.rawChars,board,"复核盘面与新手机静止截图一致");
        t.equal(JSON.stringify(bad),original,"原始漏读取证不可被改写");
        const cached=safety.recheckRoyal(bad,{},function(){return frame(board,10);},function(){});
        t.notOk(cached.recovered,"同一缓存帧不能冒充独立复核");
        t.equal(cached.result,bad,"缓存帧不能覆盖异常证据");
        const failed=safety.recheckRoyal(bad,{},function(){return {ok:false,reason:"截图失败"};},function(){});
        t.notOk(failed.recovered,"截图失败不能补回将帅");
        const thrown=safety.recheckRoyal(bad,{},function(){throw Error("capture");},function(){});
        t.notOk(thrown.recovered,"复核异常仍阻止落子");
        const moved=safety.recheckRoyal(bad,{},function(){return Object.assign(frame(board,20),{geometryChanged:true});},function(){});
        t.notOk(moved.recovered,"窗口或棋盘变化不能借将帅读数重新放行");
        const secondFailed=recognition.create({cfg:{observeFrames:1},stats:{},clock:function(){},
            opts:{recognize:function(){reads++;return reads%2 ? frame(board,reads+30) : {ok:false,reason:"第二帧失败"};}}},
            {note:function(){}});
        const incomplete=safety.recheckRoyal(bad,{},secondFailed.recognizeMerged,function(){});
        t.notOk(incomplete.recovered,"一张健康帧加一张失败帧不能算复核成功");
        const conflict=board.slice(); conflict[23]=null; conflict[22]="k";
        reads=0;
        const moving=recognition.create({cfg:{observeFrames:1},stats:{},clock:function(){},
            opts:{recognize:function(){reads++;return frame(reads===1?board:conflict,40+reads);}}},
            {note:function(){}});
        t.notOk(safety.recheckRoyal(bad,{},moving.recognizeMerged,function(){}).recovered,
            "将仍在移动，两帧不同位置时继续等待");
        let called=0;
        function forbidden(){called++;throw Error("unexpected capture");}
        t.notOk(safety.recheckRoyal(frame(board,30),{},forbidden,forbidden).attempted,"正常帧不增加截图开销");
        t.notOk(safety.recheckRoyal(bad,{independentRead:true},forbidden,forbidden).attempted,"独立核对不能再嵌套复核");
        t.equal(called,0,"上述两个分支不调用截图或等待");
        const guard=safety.createGuard();
        const first=safety.recheckRoyal(bad,{},function(){return frame(missed,50);},function(){});
        const next=safety.recheckRoyal(frame(missed,51),{},function(){return frame(missed,52);},function(){});
        t.notOk(guard.check(first.result,1,1).confirmed,"第一张独立异常只拦截");
        t.ok(guard.check(next.result,1,2).confirmed,"持续缺将帅仍必须停止");
        t.equal(guard.check(frame(board,53),1,3),null,"读回真实将帅才清除异常计数");

        const driverModule=t.load("src/automation/auto-driver.js");
        const legal=t.load("src/chess/legal-moves.js");
        const geometry=t.load("src/vision/geometry.js");
        function drive(persistent) {
            let live=board.slice(), sent=false, chosen=null, calls=0, taps=0;
            const grid=geometry.createGrid({originX:97,originY:787,stepX:136,stepY:136,
                orientation:geometry.ORIENTATION_RED_BOTTOM,version:1});
            const driver=driverModule.createDriver({allowClick:true,sleep:function(){},
                screen:function(){return {width:1280,height:2772};},locate:function(){return {ok:true,grid:grid};},
                recognize:function(request) {
                    calls++;
                    const chars=live.slice();
                    if(sent && (persistent || !request.independentRead)) chars[23]=null;
                    return Object.assign(frame(chars,calls+100),{grid:grid});
                },analyze:function(position) {
                    chosen=legal.allLegalMoves(position.board,position.side)[0].iccs;
                    return {ok:true,move:chosen,iccs:chosen,depth:16,score:0,ms:1};
                },tap:function(x,y,options) {
                    taps++;
                    if(options.kind==="to") {live=legal.applyMove(live,{iccs:chosen}).board;sent=true;}
                    return {ok:true,method:"fixture"};
                },config:{computeMode:"inline",prethink:false,predictReply:false,observeFrames:1,
                    bookOwnMoves:0,fastConfirmRoI:false,autoDeclareTurn:false}});
            driver.start();driver.tick();driver.assertTurn();
            for(let i=0;i<16;i++) {
                driver.tick();
                if(driver.machine.state()==="PAUSED" || driver.stats().confirms>0) break;
            }
            const result={paused:driver.machine.state()==="PAUSED",stats:driver.stats(),taps:taps,
                history:driver.tracker(),calls:calls};
            driver.stop();return result;
        }
        const transient=drive(false), persistent=drive(true);
        t.notOk(transient.paused,"真实落子确认链：独立新帧恢复后不误停");
        t.equal(transient.stats.confirms,1,"恢复后只确认实际那一手");
        t.equal(transient.stats.royalRecheckHits,1,"独立复核命中写入驱动统计");
        t.equal(transient.taps,2,"复核不能额外点击");
        t.ok(persistent.paused,"真实确认链：持续读不到将仍停止，不能按票据补回");
        t.equal(persistent.stats.confirms,0,"异常将帅不能把已发送票据误写成确认");
        t.equal(persistent.taps,2,"确认异常后不继续走第二手");

        const panel=t.load("src/app/panel/driver-loop.js");
        let state="PAUSED", log=[], redraws=0, guards=0, ticks=0;
        const panelState={driver:{machine:{state:function(){return state;},reason:function(){return "将帅读数异常";}},
            lastResult:function(){return bad;},tick:function(){ticks++;throw Error("paused driver tick");}},
            running:true,modeKey:"auto",lastCleanObsAt:1,lastObsOkAt:1,lastBlindRelocateAt:0};
        const actions={logLine:function(s){log.push(s);},refreshOverlay:function(){redraws++;},render:function(){},
            keepGameInFront:function(){guards++;},locateAndCalibrate:forbidden,stageMark:forbidden};
        const loop=panel.create(panelState,actions);
        for(let i=0;i<5;i++)loop.tick();
        t.equal(log.length,1,"暂停只报告一次状态，不刷旧的未知格 ?");
        t.equal(redraws,1,"暂停只清理一次绘制");
        t.equal(guards,0,"暂停期间不抢回游戏前台");
        t.equal(ticks,0,"暂停期间不误触发观察和重定位");
        t.equal(called,0,"再久的暂停也不触发盲读定位计时器");
        t.notOk(log.some(function(s){return s.indexOf("未知格 ?")>=0;}),"空结果不是一帧未知格");

        // The pause may happen within VERIFY, after the tick began in a live state.
        state="VERIFYING"; log=[]; redraws=0; called=0;
        panelState.driver.tick=function(){ticks++;state="PAUSED";return {ok:true,unknownCells:0};};
        panelState.driver.stats=function(){return {};};
        panelState.loopLock={OWNER_FLOATY:"floaty",touch:function(){return true;}};
        actions.stageMark=function(){};
        loop.tick();
        t.equal(log.length,1,"落子确认中转暂停时当轮立即处理暂停");
        t.equal(redraws,1,"确认中暂停先清掉旧标记");
        t.equal(called,0,"不能把确认前的干净帧作为继续重定位的依据");
    }
};
