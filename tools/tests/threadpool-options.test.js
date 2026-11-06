'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const calls=[];
const context=vm.createContext({module:{exports:null}});
vm.runInContext('global=globalThis;',context);
vm.runInContext(fs.readFileSync('modules/engine/src/main/assets/modules/__threads__.js','utf8'),context);
const api=context.module.exports({threads:{createPool(core,max,keepAlive){
 calls.push({core,max,keepAlive});
 return {getKeepAliveTime:()=>keepAlive,getCorePoolSize:()=>core,getMaximumPoolSize:()=>max,execute:task=>task(),shutdown:()=>{},shutdownNow:()=>{},isShutdown:()=>false};
}}},{});
assert.equal(api.pool().keepAliveTime,60000);
assert.equal(api.pool({keepAliveTime:25}).keepAliveTime,25);
assert.equal(api.pool({keepAliveTime:0}).keepAliveTime,0);
assert.equal(calls[1].keepAlive,25);
assert.throws(()=>api.pool({keepAliveTime:-1}),/keepAliveTime/);
assert.throws(()=>api.pool({keepAliveTime:NaN}),/keepAliveTime/);
let ran=false;api.pool({keepAliveTime:1}).execute(()=>{ran=true});assert.equal(ran,true);
console.log('THREAD_POOL_OPTIONS_OK checks=7');

