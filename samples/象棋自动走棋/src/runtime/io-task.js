// @engine quickjs
"use strict";

function run(job,io){
    const f=io||files;
    if(!job||typeof job.path!=="string"||typeof job.text!=="string")throw new Error("无效的 IO 任务");
    if(job.op==="append"){
        if(job.header!=null)f.write(job.path,String(job.header));
        f.append(job.path,job.text);
    }else if(job.op==="write")f.write(job.path,job.text);
    else throw new Error("不支持的 IO 操作");
    return {ok:true,path:job.path,chars:job.text.length};
}
module.exports={run:run};
