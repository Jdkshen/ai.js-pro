'use strict';
const assert=require('node:assert/strict');
const {test}=require('node:test');
const {buildSync}=require('esbuild');
const {createRequire}=require('node:module');
const path=require('node:path'),vm=require('node:vm');
const filename=path.join(__dirname,'../src/adb.ts'),originalRequire=createRequire(filename);
const code=buildSync({entryPoints:[filename],bundle:true,platform:'node',format:'cjs',write:false}).outputFiles[0].text;
function harness(reply=()=>({out:''})){
  const calls=[],module={exports:{}};
  vm.runInNewContext(code,{module,exports:module.exports,process,console,Buffer,
    require:id=>id==='child_process'?{execFile:(file,args,options,callback)=>{calls.push({file,args,options});const r=reply(args,callback);if(r)callback(r.error||null,r.out||'',r.stderr||'');}}:originalRequire(id)},{filename});
  return {calls,...module.exports};
}
test('adb devices -l 解析手机名称、未授权和离线状态',()=>{
  const {parseAdbDevices}=harness();
  const items=parseAdbDevices('* daemon started successfully\nList of devices attached\nserial1 device product:a model:MI_8 device:b\nserial2 unauthorized\nserial3 offline\n');
  assert.deepEqual(JSON.parse(JSON.stringify(items)),[{serial:'serial1',state:'device',name:'MI 8'},{serial:'serial2',state:'unauthorized',name:'serial2'},{serial:'serial3',state:'offline',name:'serial3'}]);
});
test('adb 路径含空格仍通过 execFile 参数执行，不拼 shell',async()=>{
  const h=harness(()=>({out:'List of devices attached\nserial device model:Phone\n'}));
  const usb=new h.UsbDevices(()=> 'C:/Android SDK/platform-tools/adb.exe');
  assert.equal((await usb.list()).length,1);
  assert.equal(h.calls[0].file,'C:/Android SDK/platform-tools/adb.exe');
  assert.deepEqual(Array.from(h.calls[0].args),['devices','-l']);
  assert.equal(h.calls[0].options.windowsHide,true);
});
test('两台手机独立分配转发端口，关闭时只移除本实例创建的转发',async()=>{
  const h=harness(args=>({out:args.includes('--remove')?'':args[1]==='one'?'19001':'19002'}));
  const usb=new h.UsbDevices(()=> 'adb');
  assert.equal(await usb.connectAddress('one'),'127.0.0.1:19001');
  assert.equal(await usb.connectAddress('two'),'127.0.0.1:19002');
  assert.equal(await usb.connectAddress('one'),'127.0.0.1:19001');
  assert.equal(h.calls.length,2);
  await usb.dispose();
  assert.deepEqual(h.calls.slice(2).map(c=>Array.from(c.args)),[['-s','one','forward','--remove','tcp:19001'],['-s','two','forward','--remove','tcp:19002']]);
  assert.ok(!h.calls.some(c=>c.args.includes('--remove-all')));
});
test('adb 不存在时明确报错',async()=>{
  const error=Object.assign(new Error('spawn adb ENOENT'),{code:'ENOENT'});
  const h=harness(()=>({error})),usb=new h.UsbDevices(()=> 'missing-adb');
  await assert.rejects(usb.list(),/aijspro.adbPath/);
});
test('无效转发返回值不留下可用连接或清理其它转发',async()=>{
  const h=harness(()=>({out:'not a port'})),usb=new h.UsbDevices(()=> 'adb');
  await assert.rejects(usb.connectAddress('one'),/USB 端口转发失败/);
  assert.equal(usb.addressFor('one'),undefined);
  await usb.dispose();
  assert.equal(h.calls.length,1);
});
