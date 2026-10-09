// @engine quickjs
"use strict";

// Each task callback runs serially in this engine. Cancellation invalidates the
// result; it never tears down a native call. A close message follows that call.
function start(args,environment){
    const env=environment||{},bus=env.bus||events.bus,now=env.now||Date.now;
    const schedule=env.schedule||setInterval,unschedule=env.unschedule||clearInterval;
    const quit=env.exit||exit;
    const run=env.run||(args.task==="search"?require("../automation/search-task.js").analyze:
        require("./io-task.js").run);
    let aliveAt=now(),canceledThrough=0,lastId=0,lastReply=null,stopped=false,acknowledged=false;
    const output=args.channel+".result";
    function stop(){
        if(stopped)return;stopped=true;bus.off(args.channel,receive);unschedule(timer);
        console.log("["+args.task+"-worker] 协作退出｜任务 "+lastId);quit();
    }
    function perform(job){
        if(!job||!Number.isInteger(job.id)||job.id<=0)return;
        if(job.id<=lastId){if(lastReply&&lastReply.id===job.id)bus.emit(output,lastReply);return;}
        lastId=job.id;
        let result,error="";
        try{result=job.id<=canceledThrough?{canceled:true}:run(job.payload);}
        catch(e){error=String(e&&e.message?e.message:e);}
        lastReply={type:"result",id:job.id,result:result,error:error};bus.emit(output,lastReply);
    }
    function receive(packet){
        if(stopped||!packet)return;aliveAt=now();acknowledged=true;
        if(packet.type==="control"){
            canceledThrough=Math.max(canceledThrough,Number(packet.canceledThrough)||0);
            if(packet.stop)stop();
        }else if(packet.type==="job")perform(packet);
        else if(packet.type==="finish"){
            const jobs=Array.isArray(packet.jobs)?packet.jobs:[];
            jobs.slice(0,64).forEach(perform);stop();
        }
    }
    bus.on(args.channel,receive);
    const timer=schedule(function(){
        if(now()-aliveAt>=15000){stop();return;}
        if(!acknowledged && bus.emit(output,{type:"ready"})===false)stop();
    },500);
    if(bus.emit(output,{type:"ready"})===false)stop();
    return {receive:receive};
}
module.exports={start:start};
