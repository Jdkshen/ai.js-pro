"quickjs";
// Own-app crash evidence, using Android 12+ ApplicationExitInfo without root.
var root='/sdcard/脚本/__native_crash_probe__/'+Date.now()+'/';
files.ensureDir(root);
var info={version:app.versionCode,model:device.model,sdk:device.sdkInt,root:root,exits:[]};
try {
    var manager=context.getSystemService('activity');
    var list=manager.getHistoricalProcessExitReasons(context.getPackageName(),0,32);
    var exported=0;
    for(var i=0;i<list.size();i++) {
        var exit=list.get(i);
        var row={pid:exit.getPid(),timestamp:exit.getTimestamp(),reason:exit.getReason(),status:exit.getStatus(),process:String(exit.getProcessName()),description:String(exit.getDescription())};
        info.exits.push(row);
        if((row.reason!==5 && row.reason!==1) || row.process!==String(context.getPackageName()) || row.timestamp<Date.now()-86400000 || exported>=4)continue;
        var input=null;
        try {
            input=exit.getTraceInputStream();
            if(input!==null) {
                var bytes=input.readAllBytes();
                var encoded=android.util.Base64.encodeToString(bytes,2);
                row.file=root+row.pid+'-'+row.timestamp+'.pb.txt';
                files.write(row.file,String(encoded));
                row.base64Bytes=String(encoded).length;
                exported++;
            } else row.traceUnavailable=true;
        }catch(e){row.exportError=String(e);}
        finally{if(input)try{input.close();}catch(ignored){}}
    }
}catch(e){info.error=String(e);}
files.write(root+'index.json',JSON.stringify(info,null,2));
console.log('NATIVE_CRASH_INDEX='+JSON.stringify(info));
exit();
