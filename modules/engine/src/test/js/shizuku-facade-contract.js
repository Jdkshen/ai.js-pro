'use strict';
const assert=require('assert'),fs=require('fs'),vm=require('vm'),path=require('path');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/assets/modules/shizuku-api.js'),'utf8');
const calls=[];
const context=vm.createContext({module:{exports:{}},Packages:{com:{stardust:{autojs:{runtime:{api:{shizuku:{ShizukuFacade:{
 isAvailable:()=>true,hasPermission:()=>false,stateJson:()=>'{"backendUid":null,"binderAlive":true}',requestPermission:t=>(calls.push(t),true),
 executeJson:(c,o)=>(calls.push([c,JSON.parse(o)]),'{"code":7,"result":"stdout","error":"stderr"}')
}}}}}}}}});
vm.runInContext(source,context);
const api=context.module.exports(context);
assert.equal(api.isAvailable(),true);assert.equal(api.hasPermission(),false);assert.equal(api.getState().backendUid,null);
assert.equal(api.requestPermission(),true);assert.equal(calls[0],60000);
assert.equal(api.shell('printf x; exit 7',{maxOutput:0}).result,'stdout');assert.deepStrictEqual(calls[1],['printf x; exit 7',{maxOutput:0}]);
for(const bad of [null,[],1,'100'])assert.throws(()=>api.shell('id',bad),/options/);
for(const bad of [null,'100',0,999,1.5,120001])assert.throws(()=>api.requestPermission(bad),/timeout/);
assert.throws(()=>api.shell(1),/command/);
console.log('PASS shared Shizuku facade: status/null identity, permission validation, options and nonzero stdout');
