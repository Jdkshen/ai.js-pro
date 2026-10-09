// @engine quickjs
"use strict";
const laneModule=require("../runtime/task-worker.js");

function decode(value){
    if(value&&typeof value==="object")return value;
    if(typeof value!=="string")return null;
    try{return JSON.parse(value);}catch(ignore){return null;}
}
/**
 * 泳道 deadline（提交给 task-worker 的"这次任务最多允许跑多久"）——见 2026-10-06 真机事故：
 *   · 关键步复核会在 movetime 之外再追加一段（上限 6500ms，见 critical-review.js）；
 *   · 旧公式漏了这段：预算 5000 的搜索带复核要 ~11.5s，而 deadline = 5000+5000 = 10000ms
 *     ⇒ 任务被泳道**比引擎更早**掐死 ⇒ 每次「worker 算棋超时（超过 ≈10200ms）」循环，
 *     驱动一路 RECOVERING（真机连续 56 次超时）。
 *   · 深度模式（depth>0）复核不会跑，不给这段余量。
 * 抽成纯函数：[搜索任务] 单测守它。
 */
function deadlineFor(search,graceMs){
    const s=search||{};
    const reviewSlack=(s.criticalReview===true&&!(s.depth>0))?7000:0;
    return Math.max(graceMs||4000,
        (s.timeoutMs||s.movetime||0)*((s.excludeIccs&&s.excludeIccs.length)?2:1)+5000+reviewSlack,
        (s.maxTotalMs||0)+5000);
}
function create(options){
    const o=options||{},now=o.nowMono||Date.now,log=o.log||function(){};
    let lane=null,pending=null,closed=false,localError="";
    function ensure(){
        if(lane)return;
        lane=laneModule.create({dir:o.dir,task:"search",bus:o.bus,spawn:o.spawn,
            now:now,schedule:o.schedule,unschedule:o.unschedule});
    }
    function start(position,searchOptions){
        cancel();
        if(closed||(!o.spawn&&!available()))return false;
        const search=searchOptions||{};
        const args=Object.assign({},search,{dir:o.dir,position:position});
        // 泳道 deadline：必须把关键步复核的追加段算进去（见 deadlineFor 的长注释）。
        const deadline=deadlineFor(search,o.graceMs);
        pending={id:null,at:now()};localError="";
        try{
            ensure();pending.id=lane.submit(args,deadline);
            if(pending.id==null)localError=lane.error()||"算棋任务队列不可用";
            if(lane.stats().submitted===1)log("算棋后台已启用：1 个常驻 worker，搜索与预思考共用");
        }catch(error){localError=String(error&&error.message?error.message:error);}
        // Return a failed lane as an asynchronous error; an old JNI call may
        // still be draining, so falling back to inline would overlap searches.
        return true;
    }
    function poll(){
        if(!pending){if(lane)lane.keepAlive();return {status:"idle"};}
        const elapsed=now()-pending.at;
        const result=localError?{status:"error",error:localError}:lane.take(pending.id);
        if(!result)return {status:"running",elapsedMs:elapsed};
        pending=null;localError="";
        if(result.status==="canceled")return {status:"error",error:"已取消",elapsedMs:elapsed};
        if(result.status==="done"){
            const decoded=decode(result.result);
            return decoded?{status:"done",result:decoded,elapsedMs:elapsed}:
                {status:"error",error:"worker 回传内容无效",elapsedMs:elapsed};
        }
        return {status:/超时/.test(result.error||"")?"timeout":"error",error:result.error,elapsedMs:elapsed};
    }
    function cancel(){
        if(pending&&pending.id!=null&&lane)lane.cancel(pending.id);
        pending=null;localError="";
    }
    function close(){cancel();closed=true;if(lane)lane.close(false);}
    return {dir:o.dir,graceMs:o.graceMs||4000,start:start,poll:poll,cancel:cancel,close:close,
        isRunning:function(){return !!pending||!!(lane&&lane.busy());},
        stats:function(){return lane?lane.stats():{workerStarts:0,submitted:0,completed:0,pending:0};}};
}
function available(){return laneModule.available();}
module.exports={create:create,available:available,decode:decode,deadlineFor:deadlineFor,workerTask:laneModule.workerTask};
