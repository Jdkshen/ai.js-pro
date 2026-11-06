"rhino";
// Behavioral checks use only an audit directory and an audit-owned storage namespace.
var root='/sdcard/Download/codex-three-engine-audit/';
files.ensureDir(root);
files.writeBytes(root+"rhino-fixture.png",android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAACAAAAAYCAYAAACbU/80AAAAPElEQVR4nGNgYGD4P8B41AGjDkATmNYBpv8PZAiAwAiOglEHUIqhiXgEh8CoA0YdMOqAUQeMOoBhiDkAAOs8A2YCTgtEAAAAAElFTkSuQmCC",0));
var result={engine:'rhino',version:app.versionCode,runtimeVersion:String(org.mozilla.javascript.Context.getCurrentContext().getImplementationVersion()),checks:{}};
function verify(name, action) {
  try { var value=action(); if(value!==true) throw Error('assertion: '+String(value)); result.checks[name]={ok:true}; }
  catch(e) { result.checks[name]={ok:false,error:String(e)+'\n'+String(e.stack||'')}; }
}
verify('engine',function(){return String(org.mozilla.javascript.Context.getCurrentContext().getImplementationVersion()).indexOf('Rhino')>=0;});
verify('isShuttingDown_alias',function(){return typeof isShuttingDown==='function'&&isShuttingDown===isStopped&&isShuttingDown()===false;});
verify('language_es6',function(){var values=[1,2,3].map(function(x){return x*2;});return values.join(',')==='2,4,6';});
verify('json',function(){return JSON.parse(JSON.stringify({a:'中文',b:[1,true]})).a==='中文';});
verify('java_interop',function(){return String(new java.lang.String('中文'))==='中文';});
verify('app_identity',function(){return app.versionCode>0&&String(context.getPackageName())==='com.jdkshen.aijspro';});
verify('device_dimensions',function(){return device.width>0&&device.height>0&&device.sdkInt>=21;});
verify('files_write_read',function(){var p=root+'rhino-owned.txt';files.write(p,'中文\n42');return files.read(p)==='中文\n42';});
verify('files_append',function(){var p=root+'rhino-owned.txt';files.append(p,'!');return files.read(p).slice(-1)==='!';});
verify('files_copy_rename',function(){var p=root+'rhino-owned.txt',q=root+'rhino-copy.txt',r=root+'rhino-renamed.txt';files.copy(p,q);files.rename(q,'rhino-renamed.txt');return files.exists(r)&&files.read(r)===files.read(p);});
verify('files_remove_own',function(){var p=root+'rhino-renamed.txt';return files.remove(p)&&!files.exists(p);});
verify('storage_object',function(){var s=storages.create('__codex_rhino_api_audit__');s.put('object',{text:'中文',n:4});var v=s.get('object');return v.text==='中文'&&v.n===4;});
verify('storage_missing_default',function(){return storages.create('__codex_rhino_api_audit__').get('missing',9)===9;});
verify('storage_remove',function(){var s=storages.create('__codex_rhino_api_audit__');s.remove('object');return !s.contains('object');});
verify('events_roundtrip',function(){var x=0;function f(v){x=v;}events.on('codex_audit_event',f);events.emit('codex_audit_event',42);events.removeListener('codex_audit_event',f);return x===42;});
verify('threads_join',function(){var x=0,t=threads.start(function(){x=42;});t.join(2000);return x===42;});
verify('timer_cancel',function(){var id=setTimeout(function(){throw Error('cancelled timer fired');},5000);clearTimeout(id);return id!==undefined;});
verify('shell_echo',function(){var r=shell('echo CODEX_RHINO_AUDIT');return r.code===0&&r.result.trim()==='CODEX_RHINO_AUDIT';});
verify('colors_argb',function(){var c=colors.parseColor('#80112233');return colors.alpha(c)===128&&colors.red(c)===17&&colors.green(c)===34&&colors.blue(c)===51;});
verify('image_base64_pixel_roundtrip',function(){
  var a=images.read(root+'rhino-fixture.png'),b=null;
  try { b=images.fromBase64(images.toBase64(a,'png',100));return b.getWidth()===32&&b.getHeight()===24&&images.pixel(b,4,5)===colors.parseColor('#ff009688'); }
  finally{if(b)b.recycle();a.recycle();}
});
verify('selector_construction',function(){return text('codex_missing').clickable(true).className('android.widget.Button')!=null;});
verify('sqlite_transaction_rollback',function(){
  var db=sqlite.open('codex-rhino-api-audit');
  try{
    db.exec('CREATE TABLE IF NOT EXISTS audit_probe (id INTEGER)');db.exec('DELETE FROM audit_probe');
    var errors=0,rollbacks=0,commits=0;
    db.transaction(function(tx){tx.exec('INSERT INTO audit_probe VALUES (1)');throw Error('rollback audit');})
      .on('error',function(){errors++;}).on('rollback',function(){rollbacks++;}).on('commit',function(){commits++;});
    return errors===1&&rollbacks===1&&commits===0&&db.select('SELECT id FROM audit_probe').rows.length===0;
  }finally{db.close();}
});
var names=Object.keys(result.checks);result.total=names.length;result.passed=names.filter(function(n){return result.checks[n].ok;}).length;
files.write(root+'rhino-regression-result.json',JSON.stringify(result,null,2));
console.log('RHINO_API_REGRESSION_RESULT='+JSON.stringify(result));
if(result.passed!==result.total)throw Error('RHINO_API_REGRESSION_FAILED '+result.passed+'/'+result.total);
console.log('RHINO_API_REGRESSION_OK');
exit();
