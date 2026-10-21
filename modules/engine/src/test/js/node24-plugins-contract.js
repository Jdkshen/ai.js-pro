'use strict';
// Android/JNI behavior is verified separately by tools/probes/node24-plugin-apk.js.
const assert = require('assert');
const path = require('path');
const entry = path.join(__dirname, 'fixtures/node24-plugin-index.js');
const invalidEntry = path.join(__dirname, 'fixtures/node24-plugin-invalid.js');
const calls = [];
let factory;
let mode = 'load';
const plugin = {add(a,b) {return a+b;}};
globalThis.__ajsRegisterModule = (name, fn) => { assert.strictEqual(name,'plugins'); factory=fn; };
globalThis.__autojsHostRaw = raw => {
  const r=JSON.parse(raw); calls.push(r);
  if(r.action==='load' && mode==='error') return JSON.stringify({ok:false,error:'factory failed'});
  const value = r.action==='load' ? mode==='metadata' ? {packageName:'fixture'}
    : {mainScriptPath:mode==='invalid' ? invalidEntry : entry, javaObject:{$obj:7,$class:'Fixture'}}
    : r.action==='unload' ? {unloaded:true} : null;
  return JSON.stringify({ok:true,value});
};
require(path.resolve(__dirname,'../../main/assets/node24/modules/plugins.js'));
const api = factory({wrapJavaValue(value) {assert.strictEqual(value.$obj,7); return plugin;}});
(async()=>{
  const first=await api.load('fixture');
  assert.strictEqual(first.javaObject,plugin, 'pass the real bridge object to the factory');
  assert.strictEqual(first.add(7,11),18);
  await api.unload('fixture');
  const second=await api.load('fixture');
  assert.notStrictEqual(second.helper,first.helper, 'unload clears nested modules too');
  mode='metadata'; await assert.rejects(api.load('fixture'),/真实插件入口和 Java 对象/);
  mode='error'; await assert.rejects(api.load('fixture'),/factory failed/);
  mode='invalid'; await assert.rejects(api.load('fixture'),/必须是函数/);
  assert(!require.cache[invalidEntry]);
  assert(!require.cache[require.resolve('./fixtures/node24-plugin-helper')]);
  assert.strictEqual(calls.filter(r=>r.action==='unload').length,3, 'failed initialization releases the APK');
  assert.strictEqual(api.load('org.autojs.autojspro.plugin.mlkit.ocr').backend,'mlkit');
  console.log('PASS plugin factory bridge identity, nested require cleanup and failed-load cleanup');
})().catch(e=>{console.error(e);process.exitCode=1;});
