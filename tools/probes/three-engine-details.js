// Reviewed bounded behavior contracts. Engine differences use explicit adapters.
var details = {engine:auditEngine, checks:[]};
function assertDetail(value, message) { if (!value) throw Error(message || 'assertion failed'); }
function detail(name, action) {
    try { var value=action(); assertDetail(value===true, String(value)); details.checks.push({name:name,ok:true}); }
    catch(e) { details.checks.push({name:name,ok:false,error:String(e.stack||e)}); }
}
var auditNode = auditEngine==='nodejs';
var auditFiles = auditNode ? require('files') : files;
var auditBase = '/sdcard/Download/codex-three-engine-details/'+auditEngine+'/';
if (auditNode) require('fs').mkdirSync(auditBase,{recursive:true}); else files.ensureDir(auditBase);
function putText(p,v) { if(auditNode) return auditFiles.writeText(p,v); return auditFiles.write(p,v); }
function getText(p) { return auditNode ? auditFiles.readText(p) : auditFiles.read(p); }
detail('files.utf8_empty_newline',function(){
    var p=auditBase+'text.txt'; putText(p,'中文\n😀\r\n'); assertDetail(getText(p)==='中文\n😀\r\n');
    putText(p,''); return getText(p)==='';
});
detail('files.type_and_stat',function(){
    var p=auditBase+'text.txt';
    return auditFiles.exists(p) && auditFiles.isFile(p) && auditFiles.isDir(auditBase) && !auditFiles.exists(auditBase+'missing');
});
detail('files.read_missing_throws',function(){var threw=false;try{getText(auditBase+'missing');}catch(e){threw=true;}return threw;});
detail('files.remove_only_owned',function(){var p=auditBase+'remove.txt';putText(p,'x');auditFiles.remove(p);return !auditFiles.exists(p);});
if (!auditNode) {
    detail('files.copy_rename_unicode',function(){var a=auditBase+'text.txt',b=auditBase+'copy.txt';putText(a,'abc');files.copy(a,b);files.rename(b,'中文 copy.txt');var c=auditBase+'中文 copy.txt';var ok=getText(c)==='abc';files.remove(c);return ok;});
    detail('files.listDir_filter',function(){putText(auditBase+'filter.txt','x');var rows=files.listDir(auditBase,function(n){return n==='filter.txt';});return rows.length===1&&rows[0]==='filter.txt';});
    var store=storages.create('__engine_detail_audit_'+auditEngine);
    store.clear();
    [null,false,0,'',{a:[1,null,'中文']}].forEach(function(value,index){detail('storages.value_'+index,function(){store.put('k'+index,value);return store.contains('k'+index)&&JSON.stringify(store.get('k'+index,'wrong'))===JSON.stringify(value);});});
    detail('storages.default',function(){return store.get('missing',42)===42;});
    detail('storages.undefined_rejected',function(){var threw=false;try{store.put('bad',undefined);}catch(e){threw=true;}return threw&&!store.contains('bad');});
    detail('storages.remove_and_clear',function(){store.remove('k0');assertDetail(!store.contains('k0'));store.clear();return !store.contains('k1');});
    detail('events.once_reentrant',function(){var n=0;function f(){n++;events.emit('__detail_once');}events.once('__detail_once',f);events.emit('__detail_once');events.removeAllListeners('__detail_once');return n===1;});
    detail('events.removeListener',function(){var n=0;function f(){n++;}events.on('__detail_remove',f);events.removeListener('__detail_remove',f);events.emit('__detail_remove');return n===0;});
    detail('events.multiple_args',function(){var value;function f(a,b,c){value=[a,b,c];}events.on('__detail_args',f);events.emit('__detail_args',0,false,'');events.removeListener('__detail_args',f);return JSON.stringify(value)==='[0,false,""]';});
    detail('shell.nonzero',function(){var r=shell('exit 7');return r.code===7;});
    detail('colors.alpha_roundtrip',function(){var c=colors.argb(128,17,34,51);return colors.alpha(c)===128&&colors.red(c)===17&&colors.green(c)===34&&colors.blue(c)===51;});
    detail('selector.missing_timeout',function(){var start=Date.now();var n=text('__missing_engine_detail_audit__').findOne(80);return n===null&&Date.now()-start<2500;});
    detail('engines.current_id',function(){var me=engines.myEngine();return me!=null&&typeof me.id==='number'&&me.id>=0;});
} else {
    var ds=require('datastore').createDatastore('__engine_detail_audit_node');
    ds.clearSync();
    [null,false,0,'',{a:[1,null,'中文']}].forEach(function(value,index){detail('datastore.value_'+index,function(){ds.setSync('k'+index,value);return ds.containsSync('k'+index)&&JSON.stringify(ds.getSync('k'+index,'wrong'))===JSON.stringify(value);});});
    detail('datastore.missing_undefined',function(){return ds.getSync('missing')===undefined;});
    detail('datastore.remove_and_clear',function(){ds.removeSync('k0');assertDetail(!ds.containsSync('k0'));ds.clearSync();return !ds.containsSync('k1');});
    detail('node.events.once_reentrant',function(){var e=new(require('node:events').EventEmitter)(),n=0;e.once('x',function(){n++;e.emit('x');});e.emit('x');return n===1;});
    detail('node.events.native_constructor',function(){var E=require('events');return typeof E==='function'&&E.EventEmitter===E&&typeof E.once==='function'&&new E() instanceof require('node:events');});
    detail('engines.current_id',function(){var me=require('engines').myEngine();return me!=null&&typeof me.id==='number'&&me.id>=0;});
    detail('engines.execArgv',function(){return typeof require('engines').myEngine().execArgv==='object';});
    detail('java.unicode_and_overload',function(){var StringBuilder=$autojs.java.findClass('java.lang.StringBuilder');var b=new StringBuilder();b.append('中文').append(42);return String(b.toString())==='中文42';});
    detail('node.fs.bytes',function(){var fs=require('fs'),p=auditBase+'bytes.bin';fs.writeFileSync(p,Buffer.from([0,128,255]));var a=fs.readFileSync(p);fs.unlinkSync(p);return a.length===3&&a[0]===0&&a[1]===128&&a[2]===255;});
}
var cancelled=false;
var cancelledId=setTimeout(function(){cancelled=true;},20);clearTimeout(cancelledId);
var repeated=0, repeatId=setInterval(function(){repeated++;if(repeated===2)clearInterval(repeatId);},20);
setTimeout(function(){
    detail('timers.cancel_and_interval',function(){return !cancelled&&repeated===2;});
    details.total=details.checks.length;details.passed=details.checks.filter(function(c){return c.ok;}).length;
    console.log('ENGINE_DETAILS_RESULT='+JSON.stringify(details));
    if (!auditNode) exit();
},180);
