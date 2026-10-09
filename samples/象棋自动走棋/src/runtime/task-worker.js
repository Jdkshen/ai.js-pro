// @engine quickjs
"use strict";

// One worker per lane. The shared bus copies JSON; no JS closures or native
// handles cross engines. Only one job is sent until its acknowledgement arrives.
function workerTask() {
    require(__args.dir + "/src/runtime/task-service.js").start(__args);
}
function available() {
    return typeof threads !== "undefined" && typeof threads.start === "function" &&
        typeof events !== "undefined" && events.bus && typeof events.bus.on === "function" &&
        typeof events.bus.off === "function" && typeof events.bus.emit === "function";
}
function create(options) {
    const o=options||{}, now=o.now||Date.now, bus=o.bus||events.bus;
    const spawn=o.spawn||function(args){return threads.start(workerTask,args);};
    const schedule=o.schedule||function(fn){return setInterval(fn,100);};
    const unschedule=o.unschedule||clearInterval;
    const channel=o.channel||("xiangqi."+o.task+"."+Date.now()+"."+Math.random().toString(36).slice(2));
    const output=channel+".result", queue=[], results=new Map();
    let thread=null,timer=null,ready=false,active=null,serial=0,closed=false,failed="",lastAlive=0,spawnedAt=0;
    let bytes=0,canceledThrough=0;
    const stat={workerStarts:0,submitted:0,completed:0,canceled:0,maxPending:0};
    function send(value){return bus.emit(channel,value);}
    function detach(){if(timer!=null)unschedule(timer);timer=null;bus.off(output,receive);}
    function control(stop){lastAlive=now();send({type:"control",stop:!!stop,canceledThrough:canceledThrough});}
    function finish(entry,result){
        bytes-=entry.bytes;
        results.set(entry.id,result);
        // Search consumes results explicitly; fire-and-forget IO drains via callback.
        if(typeof o.onResult==="function"){o.onResult(entry.id,result);results.delete(entry.id);}
        while(results.size>64)results.delete(results.keys().next().value);
    }
    function fail(reason){
        if(failed||closed)return;
        failed=String(reason);control(true);
        if(active){finish(active,{status:"error",error:failed});active=null;}
        while(queue.length)finish(queue.shift(),{status:"error",error:failed});
        // Never interrupt an engine inside JNI, and never start another lane
        // after a timeout. A hung native search must not overlap a new search.
        detach();
    }
    function pump(){
        if(!ready||active||closed||failed||!queue.length)return;
        active=queue.shift();active.sentAt=now();
        send({type:"job",id:active.id,payload:active.payload});
    }
    function receive(packet){
        if(!packet||closed||failed)return;
        if(packet.type==="ready"){ready=true;control(false);pump();return;}
        if(packet.type!=="result"||!active||packet.id!==active.id)return;
        const entry=active;active=null;
        const result=entry.canceled?{status:"canceled",error:"已取消"}:
            packet.error?{status:"error",error:packet.error}:{status:"done",result:packet.result};
        result.elapsedMs=now()-entry.at;
        finish(entry,result);stat.completed++;pump();
    }
    function ensure(){
        if(thread)return;
        bus.on(output,receive);spawnedAt=now();
        try{
            thread=spawn({dir:o.dir,task:o.task,channel:channel});
            if(!thread)throw new Error("未创建 worker");
            stat.workerStarts++;timer=schedule(keepAlive);
        }catch(error){fail(error);throw error;}
    }
    function submit(payload,timeoutMs){
        if(closed||failed)return null;
        const size=JSON.stringify(payload).length;
        if(queue.length+(active?1:0)>=64||bytes+size>4*1024*1024)return null;
        ensure();
        const entry={id:++serial,payload:payload,bytes:size,at:now(),sentAt:0,
            timeoutMs:Math.max(1000,Number(timeoutMs)||12000),canceled:false};
        queue.push(entry);bytes+=size;stat.submitted++;
        stat.maxPending=Math.max(stat.maxPending,queue.length+(active?1:0));pump();
        return entry.id;
    }
    function cancel(id){
        const queued=queue.findIndex(function(e){return e.id===id;});
        if(queued>=0){const entry=queue.splice(queued,1)[0];finish(entry,{status:"canceled",error:"已取消"});}
        if(active&&active.id===id){active.canceled=true;canceledThrough=Math.max(canceledThrough,id);control(false);}
        stat.canceled++;
    }
    function keepAlive(){
        if(closed||failed||!thread)return;
        let life;
        try{life=thread.getResult();}catch(error){fail(error);return;}
        if(life&&life.status!=="running"){fail(life.error||"常驻 worker 意外退出");return;}
        if(!ready&&now()-spawnedAt>5000){fail("常驻 worker 启动超时");return;}
        if(active&&now()-active.sentAt>active.timeoutMs){fail("常驻 worker 任务超时");return;}
        if(now()-lastAlive>=1000)control(false);
        pump();
    }
    function take(id){keepAlive();const result=results.get(id);if(result)results.delete(id);return result||null;}
    function close(drain){
        if(closed)return [];
        const pending=(active?[active]:[]).concat(queue);
        // During shutdown send one bounded batch. The worker can finish it even
        // after the owner's event loop has gone. Service IDs prevent double append.
        const unsent=!ready&&drain?pending.map(function(e){return e.payload;}):[];
        if(ready&&drain)send({type:"finish",jobs:pending.map(function(e){return {id:e.id,payload:e.payload};})});
        else control(true);
        closed=true;queue.length=0;active=null;bytes=0;results.clear();detach();return unsent;
    }
    return {submit:submit,take:take,cancel:cancel,close:close,keepAlive:keepAlive,
        busy:function(){keepAlive();return !!active||queue.length>0;},
        error:function(){return failed;},
        stats:function(){return Object.assign({},stat,{pending:queue.length+(active?1:0),ready:ready,failed:failed});}};
}
module.exports={create:create,available:available,workerTask:workerTask};
