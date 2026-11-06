const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');
const cpp = fs.readFileSync(path.join(root, 'modules/engine/src/main/cpp/quickjs_jni.cpp'), 'utf8');
const bootstrap = cpp.match(/const char kBootstrapScript\[\] = R"JS\(([\s\S]*?)\)JS";/);
assert(bootstrap, 'native bootstrap source exists');
new vm.Script(bootstrap[1], { filename: 'QuickJS bootstrap' });
const sqliteSource = cpp.slice(cpp.indexOf('    global.sqlite = (function ()'), cpp.indexOf('    // 内置模块表', cpp.indexOf('    global.sqlite = (function ()')));
const emitterStart = cpp.indexOf('    function makeEmitter()');
const emitterSource = cpp.slice(emitterStart, cpp.indexOf('    // `"ui"`', emitterStart));
const callbacks = new Map(), calls = [];
let callbackSeq = 0, nativeHandle = 1, version = 1;
const ctx = { global: {}, Uint8Array, ArrayBuffer,
    __aiRegisterCallback: fn => {callbacks.set(++callbackSeq, fn); return callbackSeq;},
    __aiReleaseCallback: id => callbacks.delete(id),
    __aiNativeSqliteOpen: (name, json) => {
        const options = JSON.parse(json); version = options.version;
        calls.push(['open', name, options]);
        for (const name of ['onConfigure', 'onCreate', 'onOpen']) if(options.callbacks[name]) callbacks.get(options.callbacks[name])(nativeHandle);
        return nativeHandle++;
    },
    __aiNativeSqliteCall: (handle, method, json) => {
        const args = JSON.parse(json); calls.push([method, ...args]);
        if(method === 'version') return String(version);
        if(method === 'setVersion') {version = args[0]; return 'null';}
        if(method === 'path') return JSON.stringify('/tmp/t.db');
        if(method === 'rawQuery') {
            if(args[0] === 'DUP') return JSON.stringify({columns:['n','n'], rows:[{n:2}], data:[[1,2]], types:[[1,2]]});
            return JSON.stringify({columns:['n','b'], rows:[{n:1,b:{__sqliteBlob:[0,255]}},{n:2,b:null}], data:[[1,{__sqliteBlob:[0,255]}],[2,null]], types:[[1,4],[1,0]]});
        }
        if(method === 'select' || method === 'exec') return JSON.stringify({insertId:-1, rowsAffected:0, rows:[{n:1}]});
        if(method === 'insert') return '42';
        if(method === 'update' || method === 'delete') return '2';
        return 'null';
    }
};
ctx.global.__aiRegisterCallback=ctx.__aiRegisterCallback;
ctx.global.__aiReleaseCallback=ctx.__aiReleaseCallback;
vm.createContext(ctx);vm.runInContext(emitterSource+sqliteSource,ctx);
const opened=[];
const db=ctx.global.sqlite.open('t.db',{version:3,readOnly:true},{onCreate:d=>d.execSQL('CREATE TABLE t(n)'),onOpen:d=>opened.push(d)});
assert.equal(db.version,3);assert.equal(calls[0][2].readOnly,true);assert.equal(opened[0],db);
assert(calls.some(c=>c[0]==='execSQL'));
assert.equal(db.insert('t',{n:1}),42);assert.equal(db.update('t',{n:1},null,null),2);
assert.equal(db.exec('SELECT ?',[1]).rows[0].n,1);
const cur=db.rawQuery('SELECT * FROM t');
assert.equal(cur.getPosition(),-1);assert(cur.moveToNext());assert.equal(cur.getByColumn('n'),1);
assert.deepEqual(Array.from(cur.getByColumn('b')),[0,255]);
assert.deepEqual(Array.from(cur.all(false),r=>r.n),[2]);assert.equal(cur.isClosed(),false);cur.close();assert.throws(()=>cur.pick(),/closed/);
assert.equal(db.rawQuery('SELECT * FROM t').single().n,1);
const duplicate = db.rawQuery('DUP');assert(duplicate.moveToNext());
assert.equal(duplicate.get(0),1);assert.equal(duplicate.get(1),2);
assert.equal(duplicate.getType(0),1);assert.equal(duplicate.getType(1),2);duplicate.close();
const blob=new Uint8Array([0,255]);db.execSQL('INSERT INTO t VALUES (?)',[blob]);
assert.deepEqual(calls[calls.length-1][2][0],{__sqliteBlob:[0,255]});
const events=db.transaction(tx=>tx.execSQL('INSERT INTO t VALUES(1)'));
let payload;events.on('commit',tx=>payload=tx);assert.equal(payload.handle,db.handle);
let bodyError;db.transaction(()=>{throw new Error('rollback');}).on('error',e=>bodyError=e);assert.match(bodyError.message,/rollback/);
assert.throws(()=>events.on('commit',()=>{throw new Error('assertion');}),/assertion/);
assert(callbacks.size>0);db.close();assert.equal(callbacks.size,0);assert.throws(()=>db.execSQL('x'),/closed/);

const timers=new Map(), states=new Map();let timerSeq=0,dialogSeq=0;let mainThread=false, response='confirm';
const dctx={global:{},sleep:()=>{},setInterval:fn=>{timers.set(++timerSeq,fn);return timerSeq;},clearInterval:id=>timers.delete(id),
    __aiNativeIsMainThread:()=>mainThread,
    __aiNativeDialogBuild:json=>{states.set(++dialogSeq,{props:JSON.parse(json),events:[],showing:false});return JSON.stringify({id:dialogSeq});},
    __aiNativeDialogCall:json=>{
        const {id,method,args}=JSON.parse(json);const state=states.get(id);
        if(method==='show') {state.showing=true;state.events.push({event:'show',args:[{__dialog:true}]});
            if(response!=='manual') {
                const result=response==='input'?{event:'input',args:['1 + 2',{__dialog:true}]}:response==='multi'?{event:'multi_choice',args:[[1,3],['b','d'],{__dialog:true}]}:{event:'positive',args:[{__dialog:true}]};
                state.events.push(result,{event:'dismiss',args:[{__dialog:true}]});state.showing=false;
            }return 'true';}
        if(method==='poll'){const events=state.events;state.events=[];return JSON.stringify(events);}
        if(method==='dismiss'){state.showing=false;state.events.push({event:'dismiss',args:[{__dialog:true}]});return 'null';}
        if(method==='isShowing')return JSON.stringify(state.showing);
        return 'null';
    }};
vm.createContext(dctx);vm.runInContext(fs.readFileSync(path.join(root,'modules/engine/src/main/assets/modules/qjs-dialogs-compat.js'),'utf8'),dctx);
response='manual';const dialog=dctx.global.dialogs.build({title:'Test',positive:'OK'});
assert.equal(states.get(dialog.id).showing,false);assert.equal(timers.size,0);
let multi;dialog.on('multi_choice',(indices,labels,self)=>multi=[Array.from(indices),Array.from(labels),self]);
let changed;dialog.on('input_change',(text,self)=>changed=[text,self]);
states.get(dialog.id).events.push({event:'input_change',args:['new text',{__dialog:true}]});dialog.__poll();
assert.equal(changed[0],'new text');assert.equal(changed[1],dialog);
const progressDialog=dctx.global.dialogs.build({progress:{max:10}});assert.equal(states.get(progressDialog.id).props.progress.max,10);
assert.equal(dialog.show(),dialog);assert(dialog.isShowing());
states.get(dialog.id).events.push({event:'multi_choice',args:[[1,3],['b','d'],{__dialog:true}]});dialog.__poll();
assert.deepEqual(multi.slice(0,2),[[1,3],['b','d']]);assert.equal(multi[2],dialog);dialog.dismiss();assert.equal(timers.size,0);
response='confirm';let callbackResult;
assert.equal(dctx.global.dialogs.confirm('x','y',r=>callbackResult=r),undefined);assert.equal(callbackResult,undefined);
for(const fn of Array.from(timers.values()))fn();assert.equal(callbackResult,true);assert.equal(timers.size,0);
response='input';assert.equal(dctx.global.dialogs.input('x',''),3);
response='multi';assert.deepEqual(Array.from(dctx.global.dialogs.multiChoice('x',['a','b','c','d'],[1])),[1,3]);

async function testRhino() {
    const runtime={dialogs:{multiChoice:(title,indices,items,fn)=>fn([1,3]),singleChoice:()=>{throw new Error('wrong singleChoice');}},};
    runtime.dialogs.nonUiDialogs=runtime.dialogs;
    let callback, emitted;
    runtime.dialogs.newBuilder=()=>({dialog:{emit:(...args)=>emitted=args},items:()=>{},
        itemsCallbackMultiChoice:(selected,fn)=>callback=fn,buildDialog(){return this.dialog;}});
    const rctx={module:{exports:{}},android:{os:{Looper:{myLooper:()=>1,getMainLooper:()=>1}}},threads:{currentThread:()=>1},ui:{run:fn=>fn()},colors:{parseColor:x=>x}};
    vm.createContext(rctx);vm.runInContext(fs.readFileSync(path.join(root,'modules/engine/src/main/assets/modules/__dialogs__.js'),'utf8'),rctx);
    const api=rctx.module.exports(runtime,{});
    assert.deepEqual(Array.from(await api.multiChoice('x',['a','b','c','d'],[])),[1,3]);
    const dialog=api.build({items:['a','b','c','d'],itemsSelectMode:'multiChoice',itemsSelectedIndex:[1]});
    callback(dialog,[1,3],['b','d']);assert.equal(emitted[0],'multi_choice');assert.deepEqual(Array.from(emitted[1]),[1,3]);assert.deepEqual(Array.from(emitted[2]),['b','d']);assert.equal(emitted[3],dialog);
}
async function testUiDialogPromise() {
    mainThread=true;response='confirm';
    const pending=dctx.global.dialogs.confirm('UI','Content');
    let settled=false;pending.then(()=>settled=true);await Promise.resolve();
    assert.equal(settled,false);assert.equal(timers.size,1);
    for(const fn of Array.from(timers.values()))fn();
    assert.equal(await pending,true);assert.equal(timers.size,0);
    response='manual';const cancel=dctx.global.dialogs.confirm('Cancel','Content');
    const state=states.get(dialogSeq);state.events.push({event:'cancel',args:[{__dialog:true}]},{event:'dismiss',args:[{__dialog:true}]});
    for(const fn of Array.from(timers.values()))fn();
    assert.equal(await cancel,false);assert.equal(timers.size,0);mainThread=false;
}
async function testAsyncCapture() {
    const start=cpp.indexOf('        requestScreenCaptureAsync: function');
    const source=cpp.slice(start,cpp.indexOf('        stopScreenCapture: function',start));
    const pending=new Map(),activeTimers=new Map();let callback=0,timer=0,request,started=0,stopped=0;
    const capture={global:{},setInterval:fn=>{activeTimers.set(++timer,fn);return timer;},clearInterval:id=>activeTimers.delete(id),
        startScreenCaptureStream:()=>started++,stopScreenCaptureStream:()=>stopped++,
        __aiNativeRequestScreenCaptureAsync:json=>request=JSON.parse(json),
        __aiNativeGetScreenCaptureOptions:()=>JSON.stringify({width:-1,height:720,orientation:2,density:440,async:true})};
    capture.global.__aiRegisterCallback=fn=>{pending.set(++callback,fn);return callback;};
    capture.global.__aiReleaseCallback=id=>pending.delete(id);
    vm.createContext(capture);
    vm.runInContext('var accessibilityCapture=true;function orientationOf(value){return value===undefined?0:Number(value);}var images={'+source+'};global.images=images;',capture);
    const promise=capture.global.images.requestScreenCaptureAsync({width:640.4,height:480.7,orientation:2,async:true});
    let resolved=false;promise.then(()=>resolved=true);await Promise.resolve();
    assert.equal(resolved,false);assert.equal(activeTimers.size,1);assert.equal(pending.size,1);
    assert.deepEqual([request.width,request.height,request.orientation,request.async],[640,481,2,true]);
    pending.get(request.callbackId)(true);assert.equal(await promise,true);
    assert.equal(activeTimers.size,0);assert.equal(pending.size,0);assert.equal(started,1);assert.equal(capture.accessibilityCapture,false);
    const declined=capture.global.images.requestScreenCaptureAsync();pending.get(request.callbackId)(false);
    assert.equal(await declined,false);assert.equal(activeTimers.size,0);assert.equal(pending.size,0);
    const rejected=capture.global.images.requestScreenCaptureAsync();pending.get(request.callbackId)({error:'permission failed'});
    await assert.rejects(rejected,/permission failed/);assert.equal(activeTimers.size,0);assert.equal(pending.size,0);
    const normal=capture.global.images.requestScreenCaptureAsync(320,240);pending.get(request.callbackId)(true);
    assert.equal(await normal,true);assert.equal(stopped,1);assert.equal(activeTimers.size,0);
    const options=capture.global.images.getScreenCaptureOptions();
    assert.deepEqual([options.width,options.height,options.orientation,options.density,options.async],[-1,720,2,440,true]);
}
Promise.all([testRhino(),testUiDialogPromise(),testAsyncCapture()]).then(()=>console.log('QuickJS bootstrap/SQLite/Dialog/async capture and Rhino multi-choice contract checks passed')).catch(error=>{console.error(error);process.exitCode=1;});
