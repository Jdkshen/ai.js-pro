// @engine quickjs
"use strict";
const worker=require("./task-worker.js"),task=require("./io-task.js");

function create(options){
    const o=options||{},note=o.log||function(){};
    const background=o.background==null?worker.available():o.background;
    let closed=false;
    const lane=background?worker.create(Object.assign({},o,{task:"io",onResult:function(id,result){
        if(result.status!=="done")note("后台文件保存失败 #"+id+"："+result.error);
    }})):null;
    function submit(job){
        if(closed)return false;
        if(!lane){task.run(job,o.files);return true;}
        try{
            const id=lane.submit(job,15000);
            if(id==null){note("后台文件队列不可用："+(lane.error()||"队列已满"));return false;}
            return true;
        }catch(error){note("后台文件保存不可用："+error);return false;}
    }
    function close(){
        if(closed)return;closed=true;
        // If the worker has not subscribed yet, no writes were sent. Preserve
        // those unsent final logs synchronously on this exceptional exit path.
        if(lane)lane.close(true).forEach(function(job){try{task.run(job,o.files);}catch(error){note("退出保存失败："+error);}});
    }
    return {append:function(path,text,header){return submit({op:"append",path:path,text:text,header:header});},
        write:function(path,text){return submit({op:"write",path:path,text:text});},
        close:close,stats:function(){return lane?lane.stats():{workerStarts:0,pending:0,inline:true};}};
}
module.exports={create:create};
