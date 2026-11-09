// Contract tests for Android-facing adapters. Java and Android are modeled; this is not a device test.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter: NativeEmitter } = require('node:events');
const root = path.resolve(__dirname, '../..');
const corePath = path.join(root, 'modules/engine/src/main/assets/modules/pro-modern-compat.js');
const platformPath = path.join(root, 'modules/engine/src/main/assets/modules/pro-modern-platform.js');
let checks = 0;
function check(name, run) { run(); checks++; console.log('PASS ' + name); }
async function checkAsync(name, run) { await run(); checks++; console.log('PASS ' + name); }
function plain(value) { return JSON.parse(JSON.stringify(value)); }
function collection(values) { return { toArray: () => values.slice(), iterator() { let index = 0; return { hasNext: () => index < values.length, next: () => values[index++] }; } }; }
class JavaMap {
    constructor() { this.values = new Map(); }
    put(key, value) { this.values.set(key, value); }
    get(key) { return this.values.get(key); }
    keySet() { return collection(Array.from(this.values.keys())); }
    entrySet() { return collection(Array.from(this.values, ([key, value]) => ({ getKey: () => key, getValue: () => value }))); }
}
class JavaList {
    constructor(collection) { this.values = collection ? (collection.values || collection.toArray()).slice() : []; }
    toArray() { return this.values.slice(); }
    add(value) { this.values.push(value); }
    iterator() { return collection(this.values).iterator(); }
}
class JavaFile {
    constructor(...parts) { this.path = path.posix.resolve(...parts); }
    getPath() { return this.path; }
    getCanonicalPath() { return this.path; }
    isAbsolute() { return this.path.startsWith('/'); }
    isDirectory() { return this.path === '/project/directory'; }
}
class JavaUri {
    constructor(text) { this.text = text; }
    getScheme() { return this.text.split(':')[0]; }
    toString() { return this.text; }
    static parse(text) { return new JavaUri(text); }
}
class JavaIntent {
    constructor(action) { this.action = action; this.flags = 0; this.extras = {}; this.categories = []; }
    setClassName(packageName, className) { this.packageName = packageName; this.className = className; return this; }
    putExtra(key, value) { this.extras[key] = value; return this; }
    putExtras(bundle) {Object.assign(this.extras,bundle.values);this.extraTypes=Object.assign(this.extraTypes||{},bundle.types);return this;}
    addCategory(value) { this.categories.push(value); return this; }
    setAction(value) { this.action = value; return this; }
    setFlags(value) { this.flags = value; return this; }
    addFlags(value) { this.flags |= value; return this; }
    setType(value) { this.type = value; return this; }
    setData(value) { this.data = value; return this; }
    setDataAndType(data, type) { this.data = data; this.type = type; this.paired = true; return this; }
    static createChooser(intent, title) { const chooser = new JavaIntent('chooser'); chooser.intent = intent; chooser.title = title; return chooser; }
}
Object.assign(JavaIntent, {
    FLAG_ACTIVITY_NEW_TASK: 0x10000000, FLAG_GRANT_READ_URI_PERMISSION: 1, FLAG_GRANT_WRITE_URI_PERMISSION: 2,
    ACTION_VIEW: 'android.intent.action.VIEW', ACTION_SEND: 'android.intent.action.SEND', ACTION_BATTERY_CHANGED: 'android.intent.action.BATTERY_CHANGED',
    CATEGORY_DEFAULT: 'android.intent.category.DEFAULT', EXTRA_EMAIL: 'email', EXTRA_CC: 'cc', EXTRA_BCC: 'bcc', EXTRA_SUBJECT: 'subject', EXTRA_TEXT: 'text', EXTRA_STREAM: 'stream'
});
class JavaBundle {constructor(){this.values={};this.types={};}}
for(const type of ['String','Boolean','Int','Long','Double','StringArray','BooleanArray','IntArray','LongArray','DoubleArray'])JavaBundle.prototype['put'+type]=function(key,value){this.values[key]=value;this.types[key]=type;};
class ExecutionConfig {
    constructor() { this.cwd = ''; this.args = new JavaMap(); }
    setWorkingDirectory(value) { this.cwd = value; }
    getWorkingDirectory() { return this.cwd; }
    getPath() { return ['/project']; }
    setArgument(key, value) { if(value==null)throw new Error('Kotlin non-null argument');this.args.put(key, value); }
    getArguments() { return this.args; }
}
class FileSource {
    constructor(value) { this.file = new JavaFile(value); }
    getFile() { return this.file; }
    setForcedEngine(engine) { this.engine = engine; }
}
function mockEngine(id, name, config, source) {
    return {
        getId: () => id, getClass: () => ({ getSimpleName: () => name }), getTag: key => key === 'execution.config' ? config : key === 'source' ? source : null,
        cwd: () => config.getWorkingDirectory(), forceStop() { calls.push(['forceStop', id]); }, emit(event, args) { calls.push(['engine.emit', id, event, args]); }
    };
}
const calls = [], javaCalls = [], sharedBus = new NativeEmitter();
const state = { sdk: 30, brightness: 99, writable: true, failActivity: false, failService: false, rotation: 1, shellCode: 0 };
const currentConfig = new ExecutionConfig(); currentConfig.setWorkingDirectory('/project'); currentConfig.setArgument('initial', 42);
const current = mockEngine(7, 'QuickJsJavaScriptEngine', currentConfig, new FileSource('/project/main.js'));
const rhino = mockEngine(8, 'RhinoJavaScriptEngine', currentConfig, new FileSource('/project/legacy.js'));
const manager = {
    getEngines: () => collection([current, rhino]), stopAll() { calls.push(['stopAll']); return 2; }, stopAllAndToast() { calls.push(['stopAllAndToast']); },
    execute(source, config) {
        calls.push(['execute', source, config]);
        const child = mockEngine(9, 'QuickJsJavaScriptEngine', config, source);
        return { getEngine: () => child, getConfig: () => config, getSource: () => source };
    }
};
const packageManager = {
    getInstalledApplications(flags) { calls.push(['apps', flags]); return collection([{packageName:'demo', label:'Demo'}]); },
    getInstalledPackages(flags) { calls.push(['packages', flags]); return collection([{packageName:'demo'}]); },
    getPackageArchiveInfo(file, flags) { calls.push(['apk', file, flags]); return file.endsWith('.apk') ? {packageName:'archive'} : null; },
    getApplicationLabel: info => info.label,
    getApplicationInfo(packageName) { if (packageName !== 'demo') throw new Error('NameNotFoundException'); return {packageName, label:'Demo'}; },
    getLaunchIntentForPackage: packageName => packageName === 'demo' ? new JavaIntent('launch') : null
};
const audio = {
    getStreamVolume: stream => stream + 2, getStreamMinVolume: () => 1, getStreamMaxVolume: () => 15,
    setStreamVolume(...args) { calls.push(['volume', ...args]); }
};
const services = {
    audio, phone: {getDeviceId: () => 'imei'}, batterymanager: { getIntProperty: property => property === 4 ? 78 : -1 },
    window: { getDefaultDisplay: () => ({ getRotation: () => state.rotation, getRealMetrics(metrics) { Object.assign(metrics, {widthPixels:1920,heightPixels:1080,density:3,scaledDensity:3,densityDpi:480,xdpi:400,ydpi:400}); } }) },
    activity: {getMemoryInfo: info => Object.assign(info, {availMem:2,totalMem:8,threshold:1,lowMemory:false})},
    vibrator: {vibrate: millis => calls.push(['vibrate',millis]),cancel: () => calls.push(['vibrate.cancel'])}
};
const androidContext = {
    getSystemService: name => services[name], getContentResolver: () => ({name:'resolver'}), getPackageName: () => 'com.jdkshen.aijspro', getPackageManager: () => packageManager,
    registerReceiver: () => ({getIntExtra: () => 3}),
    startActivity(intent) { if (state.failActivity) throw new Error('ActivityNotFoundException'); calls.push(['activity',intent]); },
    startService(intent) { if (state.failService) throw new Error('SecurityException'); calls.push(['service',intent]); return {name:'started'}; },
    sendBroadcast(intent) { calls.push(['broadcast',intent]); }
};
class MockBroadcastReceivers {
    constructor() { this.handles = new Map(); this.sequence = 0; this.counters = {registered:0,unregistered:0,dispatched:0,callbackErrors:0}; }
    register(context, actions, categories, schemes, permission, exported, onIntent, onError) {
        const id = 'bcr-' + (++this.sequence);
        // 与 Java 侧 BroadcastFilter.normalize 同一套语义：trim + 去空 + 去重（保序）。
        const clean = values => Array.from(new Set((values || []).map(value => String(value).trim()).filter(value => value.length > 0)));
        this.handles.set(id, {id, actions: clean(actions), categories: clean(categories), schemes: clean(schemes), onIntent, onError});
        this.counters.registered++;
        const exportFlag = state.sdk >= 33 ? (exported ? 2 : 4) : 0;
        return JSON.stringify({id, actions: clean(actions), categories: clean(categories), schemes: clean(schemes), permission,
            exported: !!exported, registeredAt: 1700000000000 + this.sequence, exportFlag});
    }
    unregister(id) { const removed = this.handles.delete(String(id)); if (removed) this.counters.unregistered++; return removed; }
    unregisterAll() { const count = this.handles.size; this.counters.unregistered += count; this.handles.clear(); return count; }
    describe() { return JSON.stringify({counters: Object.assign({}, this.counters, {active: this.handles.size}),
        active: Array.from(this.handles.values()).map(handle => ({id: handle.id, actions: handle.actions}))}); }
    dispatch(id, intent) {
        const handle = this.handles.get(String(id));
        if (!handle) return 0;   // 注销后不再回调
        this.counters.dispatched++;
        try { handle.onIntent(intent); return 1; }
        catch (error) {
            this.counters.callbackErrors++;
            if (typeof handle.onError === 'function') { try { handle.onError(error); } catch (ignored) {} }
            return 2;
        }
    }
}
const broadcastReceivers = new MockBroadcastReceivers();
const classMap = {
    'android.os.Build$VERSION': {get SDK_INT() {return state.sdk;},INCREMENTAL:'incremental',RELEASE:'11',SECURITY_PATCH:'2026-09-01'},
    'android.os.Build': {PRODUCT:'product',DEVICE:'device',BOARD:'board',BRAND:'brand',MODEL:'model',SUPPORTED_ABIS:['arm64-v8a'],ID:'id',DISPLAY:'display',BOOTLOADER:'loader',HARDWARE:'hardware',FINGERPRINT:'fingerprint',getSerial(){throw new Error('READ_PRIVILEGED_PHONE_STATE');}},
    'android.provider.Settings$Secure': {ANDROID_ID:'android_id',getString: () => 'android-id'},
    'android.provider.Settings$System': {SCREEN_BRIGHTNESS:'brightness',SCREEN_BRIGHTNESS_MODE:'mode',getInt: (_,key) => key === 'brightness' ? state.brightness : 1,putInt(_,key,value){if(!state.writable)return false; if(key==='brightness')state.brightness=value; return true;}},
    'android.media.AudioManager': {STREAM_MUSIC:3,FLAG_SHOW_UI:1,FLAG_PLAY_SOUND:4}, 'android.os.BatteryManager': {BATTERY_PROPERTY_CAPACITY:4},
    'android.util.DisplayMetrics': class {}, 'android.app.ActivityManager$MemoryInfo': class {},
    'android.os.Environment': {getExternalStorageDirectory: () => new JavaFile('/sdcard')},
    'android.content.Intent': JavaIntent, 'android.os.Bundle': JavaBundle, 'android.content.IntentFilter': class {constructor(action){this.action=action;}},
    'android.net.Uri': JavaUri, 'java.io.File': JavaFile,
    'androidx.core.content.FileProvider': {getUriForFile(_,authority,file){calls.push(['fileprovider',authority,file.getPath()]);return new JavaUri('content://'+authority+file.getPath());}},
    'android.content.pm.PackageManager': {GET_META_DATA:128,GET_ACTIVITIES:1,MATCH_SYSTEM_ONLY:0x100000},
    'android.webkit.MimeTypeMap': {getSingleton: () => ({getMimeTypeFromExtension: ext => ext === 'txt' ? 'text/plain' : null})},
    'com.stardust.autojs.ScriptEngineService': {getInstance: () => manager},
    'com.stardust.autojs.execution.ExecutionConfig': ExecutionConfig,
    'com.stardust.autojs.script.JavaScriptFileSource': FileSource, 'java.util.HashMap': JavaMap, 'java.util.ArrayList': JavaList,
    'com.stardust.autojs.core.content.ScriptBroadcastReceivers': broadcastReceivers
};
const sandbox = {
    console, setTimeout, clearTimeout, context: androidContext, events: {bus:sharedBus},
    shell(command,root){calls.push(['shell',command,root]);return {code:state.shellCode,error:'root command failed'};},
    engines: {myEngine: () => ({id:7})}, device: {getMacAddress: () => 'AA:BB:CC:DD:EE:FF'},
    files: {path: file => path.posix.resolve('/project',file)},
    Java: {type(name){javaCalls.push(name);if(!(name in classMap))throw new Error('Unexpected Java class '+name);return classMap[name];}}
};
vm.runInNewContext(fs.readFileSync(corePath,'utf8'),sandbox,{filename:corePath});
const retained = sandbox.__aiModernModules.events;
vm.runInNewContext(fs.readFileSync(platformPath,'utf8'),sandbox,{filename:platformPath});
const modules = sandbox.__aiModernModules;
const {device,Device,OS,BrightnessMode} = modules.device, app = modules.app, engines = modules.engines;
(async () => {
    check('lazy module registration does not access Java or Context',()=>assert.equal(javaCalls.length,0));
    check('keeps core event module',()=>assert.equal(modules.events,retained));
    check('stable Device singleton and enum contract',()=>{assert.equal(device,Device.instance);assert.equal(BrightnessMode.manual,0);assert.equal(BrightnessMode.automatic,1);});
    check('Product is the five-field object',()=>assert.deepEqual(plain(device.product),{name:'product',device:'device',board:'board',brand:'brand',model:'model'}));
    check('OS constants and names match Pro table',()=>{assert.equal(OS.ANDROID_N_MR1.code,25);assert.equal(OS.ANDROID_S.name,'12');assert.equal(OS.sdkName,'11');});
    check('OS requirement throws supplied message',()=>assert.throws(()=>OS.requiresAndroidVersion(OS.ANDROID_S,'upgrade'),/upgrade/));
    check('new Android SDK name is unknown as Pro',()=>{state.sdk=36;assert.equal(OS.sdkName,'unknown');state.sdk=30;});
    check('identifier permission exception propagates',()=>assert.throws(()=>device.serial,/READ_PRIVILEGED_PHONE_STATE/));
    check('Android ID and CPU ABI',()=>{assert.equal(device.androidId,'android-id');assert.deepEqual(plain(device.cpuApis),['arm64-v8a']);});
    check('volume stream and flags resolve to Android constants',()=>{assert.equal(device.getVolume('music'),5);device.setVolume('music',8,['show_ui','play_sound']);assert.deepEqual(calls.at(-1),['volume',3,8,5]);});
    check('volume range uses actual minimum and maximum',()=>assert.deepEqual(plain(device.getVolumeRange('music')),{min:1,max:15}));
    check('old Android volume minimum falls back to zero',()=>{state.sdk=27;assert.equal(device.getVolumeRange('music').min,0);state.sdk=30;});
    check('invalid stream and flag throw',()=>{assert.throws(()=>device.getVolume('bad'),/Unknown audio stream/);assert.throws(()=>device.setVolume('music',1,['bad']),/Unknown volume flag/);});
    check('screen reference is portrait while metrics are current orientation',()=>{assert.equal(device.screenWidth,1080);assert.equal(device.screenHeight,1920);assert.equal(device.displayMetrics.widthPixels,1920);});
    check('battery level and plug bitmask',()=>{assert.equal(device.battery,78);assert.deepEqual(plain(device.batteryPluggedTypes),['ac','usb']);});
    check('brightness writes through Context',()=>{device.brightness=123;assert.equal(device.brightness,123);});
    check('failed brightness write throws',()=>{state.writable=false;assert.throws(()=>{device.brightness=1;},/Cannot set screen brightness/);state.writable=true;});
    check('memory and storage expose actual values',()=>{assert.deepEqual(plain(device.memoryInfo),{availMem:2,totalMem:8,threshold:1,lowMemory:false});assert.equal(device.externalStorageDirectory,'/sdcard');});
    check('vibration invokes service',()=>{device.vibrate(50);device.cancelVibration();assert.deepEqual(calls.at(-2),['vibrate',50]);assert.deepEqual(calls.at(-1),['vibrate.cancel']);});
    check('battery receiver unavailability is explicit',()=>assert.throws(()=>device.once('battery-changed',()=>{}),/not available/));
    check('makeIntent includes extras categories action flags and paired data/type',()=>{const intent=app.makeIntent({packageName:'demo',className:'Main',extras:{text:'hi',number:3},category:['c1','c2'],action:'VIEW',flags:['activity_new_task',1],data:'https://example.test',type:'text/plain'});assert.equal(intent.packageName,'demo');assert.equal(intent.className,'Main');assert.deepEqual(plain(intent.extras),{text:'hi',number:3});assert.deepEqual(plain(intent.categories),['c1','c2']);assert.equal(intent.action,'android.intent.action.VIEW');assert.equal(intent.flags,0x10000001);assert.equal(intent.paired,true);});
    check('numeric and array Intent extras have explicit Bundle types',()=>{const intent=app.makeIntent({extras:{small:3,big:2147483648,real:1.5,bool:true,strings:['a'],numbers:[1,2],longs:[2147483648],doubles:[1.5],bools:[false],empty:null}});assert.deepEqual(plain(intent.extraTypes),{small:'Int',big:'Long',real:'Double',bool:'Boolean',strings:'StringArray',numbers:'IntArray',longs:'LongArray',doubles:'DoubleArray',bools:'BooleanArray',empty:'String'});assert.throws(()=>app.makeIntent({extras:{object:{a:1}}}),/require primitives/);});
    check('URI objects pass through unchanged',()=>{const uri=JavaUri.parse('content://demo/a');assert.equal(app.parseUri(uri),uri);});
    check('file URI uses actual FileProvider authority',()=>{assert.equal(String(app.parseUri('file:///sdcard/hello.txt')),'content://com.jdkshen.aijspro.fileprovider/sdcard/hello.txt');assert.deepEqual(calls.at(-1),['fileprovider','com.jdkshen.aijspro.fileprovider','/sdcard/hello.txt']);});
    check('Intent flags reject unsupported strings',()=>assert.throws(()=>app.makeIntent({flags:'missing'}),/Unknown Intent flag/));
    await checkAsync('startActivity is Promise<void> with NEW_TASK',async()=>{const promise=app.startActivity({action:'VIEW'});assert.equal(typeof promise.then,'function');assert.equal(await promise,undefined);assert.equal(calls.at(-1)[1].flags,0x10000000);});
    await checkAsync('console activity string maps to the real app class',async()=>{await app.startActivity('console');assert.equal(calls.at(-1)[1].className,'com.jdkshen.aijspro.ui.log.LogActivity');});
    await checkAsync('Android activity failure rejects Promise',async()=>{state.failActivity=true;await assert.rejects(app.startActivity({action:'VIEW'}),/ActivityNotFoundException/);state.failActivity=false;});
    await checkAsync('startService ignores ComponentName and returns void',async()=>{assert.equal(await app.startService({action:'SERVICE'}),undefined);assert.equal(calls.at(-1)[0],'service');});
    await checkAsync('Android service failure rejects Promise',async()=>{state.failService=true;await assert.rejects(app.startService({action:'SERVICE'}),/SecurityException/);state.failService=false;});
    await checkAsync('broadcast is Promise<void> and calls real Context',async()=>{assert.equal(await app.sendBroadcast({action:'demo.event'}),undefined);assert.equal(calls.at(-1)[0],'broadcast');});
    await checkAsync('root action uses the actual bootstrap global shell(command, true)',async()=>{await app.startActivity({root:true,action:'VIEW',extras:{text:"a'b'c"}});const call=calls.at(-1);assert.equal(call[0],'shell');assert.equal(call[2],true);assert.equal(modules.shell,undefined);assert.ok(call[1].includes("'a'\\''b'\\''c'"));});
    await checkAsync('nonzero root shell exit rejects',async()=>{state.shellCode=1;await assert.rejects(app.sendBroadcast({root:true,action:'demo.event'}),/root command failed/);state.shellCode=0;});
    check('shell intent typed extras and escaping',()=>{const cmd=app.intentToShell({extras:{small:7,big:2147483648,real:1.5,ok:true,tags:['a,b','c']},category:'semi;colon',data:'https://example.test/a?x=1&y=2'});assert.ok(cmd.includes('--ei'));assert.ok(cmd.includes('--el'));assert.ok(cmd.includes('--ef'));assert.ok(cmd.includes('--ez'));assert.ok(cmd.includes('a\\,b,c'));assert.ok(cmd.includes("-c 'semi;colon'"));assert.ok(cmd.includes("-d 'https://example.test/a?x=1&y=2'"));});
    check('shell invalid arrays and nonserializable extras reject',()=>{assert.throws(()=>app.intentToShell({extras:{a:[]}}),/Empty array/);assert.throws(()=>app.intentToShell({extras:{a:[true]}}),/not available/);assert.throws(()=>app.intentToShell({extras:{a:{}}}),/must be/);});
    check('app lookup and launch boolean contract',()=>{assert.equal(app.getPackageName('Demo'),'demo');assert.equal(app.getPackageName('unknown'),null);assert.equal(app.getAppName('demo'),'Demo');assert.equal(app.getAppName('missing'),null);assert.equal(app.launch('missing'),false);assert.equal(app.launchApp('Demo'),true);});
    check('PackageManager filters preserve metadata and selected bits',()=>{assert.equal(app.getInstalledApps({get:['activities'],match:['system_only']}).length,1);assert.deepEqual(calls.at(-1),['apps',128|1|0x100000]);app.getInstalledPackages();assert.deepEqual(calls.at(-1),['packages',128]);});
    check('PackageManager rejects malformed filters',()=>{assert.throws(()=>app.getInstalledApps({get:'activities'}),/must be an array/);assert.throws(()=>app.getInstalledApps({match:['bad']}),/Unknown PackageManager flag/);});
    check('APK info resolves path and null parsing result',()=>{assert.equal(app.getApkInfo('demo.apk').packageName,'archive');assert.equal(app.getApkInfo('bad.txt'),null);});
    check('file viewer sends MIME type and grant flags',()=>{app.viewFile('notes.txt');const intent=calls.at(-1)[1];assert.equal(intent.type,'text/plain');assert.equal(intent.flags,0x10000003);assert.equal(intent.data.getScheme(),'content');});
    check('email builds address arrays and chooser',()=>{app.sendEmail({email:'a@example.test',cc:['b@example.test'],text:'message',chooserTitle:'Choose'});const chooser=calls.at(-1)[1];assert.equal(chooser.title,'Choose');assert.deepEqual(plain(chooser.intent.extras.email),['a@example.test']);assert.equal(chooser.intent.type,'message/rfc822');});
    check('script broadcast receiver: 默认 NOT_EXPORTED、句柄可读、注销幂等且注销后不回调',()=>{
        state.sdk=30;
        const received=[];
        const handle=app.registerBroadcastReceiver('  demo.event  ',intent=>received.push(intent.action));
        assert.equal(handle.id,'bcr-1');
        assert.deepEqual(plain(handle.actions),['demo.event']);
        assert.equal(handle.exported,false);
        assert.equal(handle.exportFlag,0);                 // API<33 不带 flags
        assert.equal(String(handle),'bcr-1');
        assert.ok(handle.registeredAt>0);
        assert.equal(broadcastReceivers.dispatch(handle.id,{action:'demo.event'}),1);
        assert.deepEqual(received,['demo.event']);
        assert.equal(app.unregisterBroadcastReceiver(handle),true);   // 传句柄对象
        assert.equal(app.unregisterBroadcastReceiver(handle.id),false); // 幂等
        assert.equal(handle.active,false);
        assert.equal(broadcastReceivers.dispatch(handle.id,{action:'demo.event'}),0);
        assert.deepEqual(received,['demo.event']);          // 注销后不再回调
        state.sdk=33;
        const errors=[];
        const exported=app.registerBroadcastReceiver({action:['demo.a','demo.a',' demo.b '],categories:['demo.cat'],schemes:['https']},
            {onReceive:()=>{throw new Error('callback blew up');},onError:error=>errors.push(String(error.message)),exported:true});
        assert.deepEqual(plain(exported.actions),['demo.a','demo.b']);   // trim + 去重
        assert.deepEqual(plain(exported.categories),['demo.cat']);
        assert.deepEqual(plain(exported.schemes),['https']);
        assert.equal(exported.exportFlag,2);                // RECEIVER_EXPORTED（显式覆盖）
        assert.equal(broadcastReceivers.dispatch(exported.id,{action:'demo.a'}),2);   // 异常被隔离
        assert.deepEqual(errors,['callback blew up']);      // onError 收到
        assert.equal(broadcastReceivers.dispatch(exported.id,{action:'demo.a'}),2);   // 进程没被拖垮，可继续收
        assert.equal(app.unregisterAllBroadcastReceivers(),1);
        const counters=JSON.parse(String(broadcastReceivers.describe())).counters;
        assert.equal(counters.registered,2);
        assert.equal(counters.unregistered,2);
        assert.equal(counters.active,0);
        assert.equal(counters.callbackErrors,2);
        assert.throws(()=>app.registerBroadcastReceiver('',()=>{}),/至少要有一个非空 action/);
        assert.throws(()=>app.registerBroadcastReceiver('demo.a','not a function'),/callback 必须是函数/);
        const limitations=sandbox.__aiModernModuleCoverage.app.limitations.join(' ');
        assert.ok(limitations.includes('RECEIVER_NOT_EXPORTED'));
        // K40 dev-1443 实测更正：shell/system（含 adb am broadcast）仍会送达 NOT_EXPORTED，别拿它当反例。
        assert.ok(limitations.includes('shell/system') && limitations.includes('am broadcast'));
        assert.ok(limitations.includes('static manifest registration'));
        state.sdk=30;
    });
    check('current engine is stable and backed by Java source/config',()=>{assert.equal(engines.myEngine(),engines.myEngine());assert.equal(engines.myEngine().id,7);assert.equal(engines.myEngine().sourceFile,'/project/main.js');assert.equal(engines.myEngine().workingDirectory,'/project');assert.equal(engines.myEngine().execArgv.initial,42);assert.equal(engines.myEngine().executionConfig.workingDirectory,'/project');});
    check('private collection implementation is copied through public ArrayList',()=>{const saved=manager.getEngines;manager.getEngines=()=>({__javaHandle:17,values:[current,rhino],toArray(){throw new Error('IllegalAccessException');}});assert.deepEqual(plain(engines.getRunningEngines().map(engine=>engine.id)),[7,8]);manager.getEngines=saved;});
    check('private Map entries are avoided and nested collection arguments become JS values',()=>{const nested=new JavaMap(),list=new JavaList();list.add(1);list.add(null);nested.put('list',list);currentConfig.setArgument('nested',nested);const keys=currentConfig.args.keySet;currentConfig.args.keySet=()=>({__javaHandle:18,values:Array.from(currentConfig.args.values.keys()),toArray(){throw new Error('IllegalAccessException');}});assert.deepEqual(plain(engines.myEngine().execArgv.nested),{list:[1,null]});currentConfig.args.keySet=keys;});
    check('all running engines use global service collection',()=>assert.deepEqual(plain(engines.getRunningEngines().map(engine=>engine.id)),[7,8]));
    check('forceStop reaches real engine',()=>{engines.getRunningEngines()[1].forceStop();assert.deepEqual(calls.at(-1),['forceStop',8]);});
    check('stopAll toast option and void return',()=>{assert.equal(engines.stopAll({toast:true}),undefined);assert.deepEqual(calls.at(-1),['stopAllAndToast']);assert.equal(engines.stopAll(),undefined);assert.deepEqual(calls.at(-1),['stopAll']);});
    check('direct engine event and broadcast delivered once to self',()=>{let sum=0;const self=engines.myEngine();const listener=value=>{sum+=value.n;};self.on('contract',listener);self.emit('contract',{n:2});engines.broadcast('contract',{n:3});assert.equal(sum,5);assert.deepEqual(plain(calls.at(-1).slice(0,3)),['engine.emit',8,'contract']);assert.equal(calls.at(-1)[3][0].get('n'),3);self.off('contract',listener);assert.equal(self.listenerCount('contract'),0);assert.equal(sharedBus.listenerCount('__ai_modern_engine__:7:contract'),0);});
    check('once removes both shared bus subscriptions',()=>{let count=0;const self=engines.myEngine();self.once('once',()=>count++);engines.broadcast('once');engines.broadcast('once');assert.equal(count,1);assert.equal(sharedBus.listenerCount('__ai_modern_broadcast__:once'),0);});
    check('removeAll removes per-event subscription and invalid listener does not leak',()=>{const self=engines.myEngine();self.on('cleanup',()=>{});self.removeAllListeners();assert.equal(sharedBus.listenerCount('__ai_modern_engine__:7:cleanup'),0);assert.throws(()=>self.on('bad',null),/Listener must be/);assert.equal(sharedBus.listenerCount('__ai_modern_engine__:7:bad'),0);});
    check('nonserializable event data throws before emit',()=>{const cycle={};cycle.self=cycle;assert.throws(()=>engines.broadcast('bad',cycle),/circular/i);assert.throws(()=>engines.myEngine().emit('bad',undefined),/JSON serializable/);});
    await checkAsync('execScriptFile uses actual source/config and engine promise',async()=>{const execution=engines.execScriptFile('child.js',{workingDirectory:'work',arguments:{plain:4,empty:null,nested:{list:[1,'x',null]}}});const call=calls.at(-1);assert.equal(call[0],'execute');assert.equal(call[1].getFile().getPath(),'/project/child.js');assert.equal(call[1].engine,'quickjs');assert.equal(call[2].getWorkingDirectory(),'/project/work');assert.equal(call[2].getArguments().get('plain'),4);assert.equal(call[2].getArguments().get('empty'),null);assert.equal(call[2].getArguments().get('nested').get('list').values[2],null);assert.equal(execution.sourceFile,'/project/child.js');assert.equal(execution.engineOrNull.id,9);assert.equal((await execution.engine()).id,9);assert.equal(execution.engine(),execution.engine());assert.throws(()=>execution.on('start',()=>{}),/not available/);});
    check('project directories and V8 handler are explicit gaps',()=>{assert.throws(()=>engines.execScriptFile('/project/directory'),/not available/);assert.throws(()=>engines.myEngine().handler,/not available/);});
    check('coverage includes exports and known missing capabilities',()=>{for(const name of ['device','app','engines']){assert.ok(sandbox.__aiModernModuleCoverage[name].exports.length);assert.ok(sandbox.__aiModernModuleCoverage[name].limitations.length);}assert.ok(sandbox.__aiModernModuleCoverage.engines.limitations.some(text=>text.includes('WorkManager')));});
    console.log(`RESULT ${checks} contract checks passed (modeled Java/Android; no device claim)`);
})().catch(error=>{console.error(error);process.exitCode=1;});
