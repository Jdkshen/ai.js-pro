'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {EventEmitter} = require('node:events');
let checks = 0;
function check(fn) { fn(); checks++; }
const intervals = new Set();
class Frame {
    constructor(width=4,height=2) { this.width=width; this.height=height; this.recycled=false; }
    recycle() { this.recycled=true; }
}
const events = new EventEmitter();
const calls = [];
const mats = [];
const images = Object.assign(events, {
    read(file) { if (file === 'missing') throw new Error('missing'); return new Frame(); },
    load() { return new Frame(); },
    matToImage() { return new Frame(); },
    copy(frame) { return new Frame(frame.width,frame.height); },
    resize(frame,size,method) { calls.push(['resize',size,method]); return new Frame(size[0],size[1]); },
    scale(frame,fx,fy) { return new Frame(frame.width*fx,frame.height*fy); },
    clip(frame,x,y,w,h) { return new Frame(w,h); },
    grayscale(frame) { return new Frame(frame.width,frame.height); },
    rotate(frame) { return new Frame(frame.width,frame.height); },
    pixel() { return 0xff123456|0; },
    toMat() { const mat={releaseCount:0,release(){this.releaseCount++}}; mats.push(mat); return mat; },
    toBytes() { return new Uint8Array([0,128,255,65]); },
    fromBytes(bytes) { calls.push(['decode', Array.from(bytes)]); return new Frame(); },
    save(frame,file,format,quality) { calls.push(['save',file,format,quality]); return true; },
    findImage(frame,template,options) { calls.push(['findImage',options]); return {x:1,y:0}; },
    findImageInRegion() { return {x:1,y:0}; },
    findColor() { return {x:1,y:0}; },
    findMultiColors(frame,color,path,options) { calls.push(['multi',color,path,options]); return {x:1,y:0}; },
    matchTemplate() { return {matches:[{point:{x:1,y:0},similarity:.9}]}; },
    requestScreenCapture(options) { calls.push(['capture',options]); return true; },
    requestScreenCaptureAsync(options) { calls.push(['asyncCapture']); return Promise.resolve(this.requestScreenCapture(options)); },
    stopScreenCapture() { calls.push(['stop']); }
});
let nextDialogAction = 'positive';
let lastDialogProps;
const builtDialogs = [];
class Dialog extends EventEmitter {
    constructor(props) { super(); this.props=props; this.showCount=0; this.dismissCount=0; this.closeCount=0; builtDialogs.push(this); }
    show() {
        this.showCount++;
        setTimeout(() => {
            if (nextDialogAction === 'input') this.emit('input','typed',this);
            else if (nextDialogAction === 'multi_choice') this.emit('multi_choice',[0,2],['a','c'],this);
            else if (nextDialogAction === 'item_select') this.emit('item_select',1,'b',this);
            else if (nextDialogAction !== 'cancel') this.emit(nextDialogAction,this);
            this.emit('dismiss',this);
        },1);
        return this;
    }
    dismiss() { this.dismissCount++; this.emit('dismiss',this); return this; }
    close() { this.closeCount++; this.emit('dismiss',this); return this; }
}
const ctx = vm.createContext({
    NativeFrame:Frame, images, colors:{parseColor:value=>parseInt(value.replace('#',''),16)|0},
    dialogs:{build:props=>{lastDialogProps=props;return new Dialog(props)},setDefaultDialogType:()=>{}},
    setTimeout, clearTimeout, setInterval:fn=>{const id=setInterval(fn,1000);intervals.add(id);return id;},clearInterval:id=>{clearInterval(id);intervals.delete(id);},
    device:{sdkInt:36}, context:{getSystemService:()=>({hasPrimaryClip:()=>true,clearPrimaryClip:()=>{}})},
    getClip:()=>'', setClip:()=>{}, toast:()=>{}, console
});
vm.runInContext(fs.readFileSync('modules/engine/src/main/assets/modules/pro-modern-compat.js','utf8'),ctx);
(async () => {
    const m=ctx.__aiModernModules;
    check(()=>assert.equal(typeof m.image.readImage,'function'));
    const img=await m.image.readImage('picture');
    check(()=>assert.ok(img instanceof m.image.Image));
    check(()=>assert.equal(img.pixel(1,0).value,0xff123456|0));
    check(()=>assert.equal(img.width,4));
    const scaled=await img.resize(2,3,1);
    check(()=>assert.equal(scaled.height,3));
    check(()=>assert.equal(calls.find(call=>call[0]==='resize')[2],'LINEAR'));
    await assert.rejects(m.image.readImage('missing'),/missing/); checks++;
    const bytes=await m.image.encodeImageToBuffer(img);
    check(()=>assert.ok(m.buffer.Buffer.isBuffer(bytes)));
    check(()=>assert.equal(bytes.toString('hex'),'0080ff41'));
    for (const text of ['','ASCII','中文','x😀y','\ud800']) {
        const buffer=m.buffer.Buffer.from(text);
        check(()=>assert.equal(buffer.toString('hex'),Buffer.from(text).toString('hex')));
        check(()=>assert.equal(buffer.toString(),Buffer.from(text).toString()));
        check(()=>assert.equal(buffer.toString('base64'),Buffer.from(text).toString('base64')));
        check(()=>assert.equal(m.buffer.Buffer.from(buffer.toString('base64'),'base64').toString(),Buffer.from(text).toString()));
    }
    await m.image.decodeImageFromBuffer(bytes);
    check(()=>assert.deepEqual(calls.find(call=>call[0]==='decode')[1],[0,128,255,65]));
    await m.image.writeImage(img,'photo.jpg',75);
    check(()=>assert.deepEqual(calls.find(call=>call[0]==='save'),['save','photo.jpg','jpg',75]));
    const matches=await m.image.matchTemplate(img,scaled,{});
    check(()=>assert.ok(Array.isArray(matches)));
    let evaluations=0; const lazy=m.lang.lazyProp(()=>++evaluations);
    check(()=>assert.equal(lazy.get(),1));
    check(()=>assert.equal(lazy.get(),1));
    const deferred=new m.lang.Deferred();
    check(()=>assert.equal(deferred.resolve(7),true));
    check(()=>assert.equal(deferred.resolve(8),false));
    check(()=>assert.equal(deferred.value,7));
    assert.equal(await deferred.promise(),7); checks++;
    let recursive=0; const emitter=new m.events();
    emitter.once('work',()=>{recursive++;emitter.emit('work')});
    emitter.emit('work'); check(()=>assert.equal(recursive,1));
    const dialog=await m.dialogs.buildDialog({title:'lazy'});
    check(()=>assert.equal(dialog.showCount,0));
    assert.equal(await m.dialogs.showConfirmDialog('test',{positive:'Yes',negative:'No',type:'overlay'}),true); checks++;
    check(()=>assert.equal(lastDialogProps.positive,'Yes'));
    check(()=>assert.equal(lastDialogProps.type,'overlay'));
    nextDialogAction='cancel';
    assert.equal(await m.dialogs.showInputDialog('input','initial'),null); checks++;
    nextDialogAction='input';
    assert.equal(await m.dialogs.showInputDialog('input','initial'),'typed'); checks++;
    nextDialogAction='multi_choice';
    assert.deepEqual(await m.dialogs.showMultiChoiceDialog('pick',['a','b','c'],[0]),[0,2]); checks++;
    const capture=await m.media_projection.requestScreenCapture({orientation:3,width:320,height:240});
    check(()=>assert.equal(calls.find(call=>call[0]==='capture')[1].orientation,2));
    check(()=>assert.equal(calls.find(call=>call[0]==='capture')[1].async,true));
    check(()=>assert.ok(calls.find(call=>call[0]==='asyncCapture')));
    check(()=>assert.equal(capture.latestImageOrNull(),null));
    const first=capture.nextImage(); images.emit('screen_capture',new Frame(320,240));
    const captured=await first; check(()=>assert.equal(captured.width,320));
    const pending=capture.nextImage(); capture.stop();
    await assert.rejects(pending,/stopped/); checks++;
    check(()=>assert.equal(images.listenerCount('screen_capture'),0));
    images.requestScreenCaptureAsync = opt => { calls.push(['deniedOptions',opt]); return Promise.resolve(false); };
    await assert.rejects(m.media_projection.requestScreenCapture(), /denied/); checks++;
    check(()=>assert.equal(calls.find(call=>call[0]==='deniedOptions')[1].orientation,-1));
    check(()=>assert.equal(intervals.size,0));
    // Color's actual Pro parser accepts only #RRGGBB/#AARRGGBB strings.
    check(()=>assert.equal(new m.color.Color(0xffff0000).toString(),'Color(0xffff0000)'));
    check(()=>assert.equal(m.color.Color.parse('#112233').value,0xff112233|0));
    check(()=>assert.equal(m.color.Color.parse('#80112233').value,0x80112233|0));
    check(()=>assert.equal(m.color.Color.parse('red'),null));
    check(()=>assert.equal(m.color.Color.parse('#123'),null));
    check(()=>assert.throws(()=>m.color.Color.parse(0xff112233),/startsWith/));
    const parsed=m.color.Color.parse('#123456');
    check(()=>assert.equal(m.image.detectsColor(img,parsed,0,0),true));
    check(()=>assert.equal(m.image.detectsColor(img,0xff123456|0,0,0),true));
    // Each Image owns one Mat, including an explicitly supplied Mat.
    const imageMat=img.mat;
    check(()=>assert.equal(img.mat,imageMat));
    check(()=>assert.equal(mats.length,1));
    const ownedMat={releaseCount:0,release(){this.releaseCount++}};
    const fromMat=new m.image.Image(ownedMat);
    check(()=>assert.equal(fromMat.mat,ownedMat));
    fromMat.recycle(); fromMat.recycle();
    check(()=>assert.equal(ownedMat.releaseCount,1));
    check(()=>assert.throws(()=>fromMat.mat,/recycled/));
    // Simple dialog wrappers own hidden handles; advanced objects remain reusable.
    check(()=>assert.equal(dialog.closeCount,0));
    check(()=>assert.ok(builtDialogs.slice(1).every(item=>item.closeCount===1)));
    const colorProps={titleColor:parsed,contentColor:parsed,itemsColor:parsed,positiveColor:parsed,negativeColor:parsed,neutralColor:parsed,buttonRippleColor:parsed};
    const coloredDialog=await m.dialogs.buildDialog(colorProps);
    check(()=>assert.ok(Object.keys(colorProps).every(key=>coloredDialog.props[key]===parsed.value)));
    check(()=>assert.ok(Object.keys(colorProps).every(key=>colorProps[key]===parsed)));
    coloredDialog.dismiss(); check(()=>assert.equal(coloredDialog.closeCount,0));
    coloredDialog.close();
    nextDialogAction='positive';
    assert.equal(await m.dialogs.showConfirmDialog('colored',colorProps),true); checks++;
    check(()=>assert.equal(lastDialogProps.positiveColor,parsed.value));
    check(()=>assert.equal(builtDialogs[builtDialogs.length-1].closeCount,1));
    const build=ctx.dialogs.build;
    let failedDialog;
    ctx.dialogs.build=props=>{failedDialog=new Dialog(props);failedDialog.show=()=>{throw new Error('show failed')};return failedDialog};
    await assert.rejects(m.dialogs.showConfirmDialog('failed'),/show failed/); checks++;
    check(()=>assert.equal(failedDialog.closeCount,1));
    ctx.dialogs.build=build;
    // Background frames remain pending until a reader consumes the latest one.
    const fastRequest=images.requestScreenCaptureAsync;
    images.requestScreenCaptureAsync=()=>Promise.resolve(true);
    const buffered=await m.media_projection.requestScreenCapture();
    const frameA=new Frame(11,5),frameB=new Frame(12,5),frameC=new Frame(13,5);
    images.emit('screen_capture',frameA);
    await buffered.awaitForImageAvailable();
    const imageA=await buffered.nextImage();
    check(()=>assert.equal(imageA.width,11));
    const matA=imageA.mat;
    images.emit('screen_capture',frameB);
    check(()=>assert.equal(imageA.width,11));
    images.emit('screen_capture',frameC);
    check(()=>assert.equal(frameB.recycled,true));
    check(()=>assert.equal(frameA.recycled,false));
    await buffered.awaitForImageAvailable();
    check(()=>assert.equal(frameA.recycled,false));
    const imageC=await buffered.nextImage();
    check(()=>assert.equal(imageC.width,13));
    check(()=>assert.equal(frameA.recycled,true));
    check(()=>assert.equal(matA.releaseCount,1));
    check(()=>assert.throws(()=>imageA.width,/recycled/));
    const waitingA=buffered.nextImage(),waitingB=buffered.nextImage();
    const frameD=new Frame(14,5);images.emit('screen_capture',frameD);
    const [imageD1,imageD2]=await Promise.all([waitingA,waitingB]);
    check(()=>assert.equal(imageD1,imageD2));
    check(()=>assert.equal(imageD1.width,14));
    const unused=new Frame(15,5);images.emit('screen_capture',unused);
    buffered.stop();
    check(()=>assert.equal(frameD.recycled,true));
    check(()=>assert.equal(unused.recycled,true));
    check(()=>assert.equal(buffered.latestImageOrNull(),null));
    await assert.rejects(buffered.nextImage(),error=>error.name==='ImageUnavailableError'); checks++;
    // Concurrent requests are serialized; an obsolete capturer cannot stop a new stream.
    const requests=[];
    images.requestScreenCaptureAsync=options=>new Promise(resolve=>requests.push({options,resolve}));
    const requestA=m.media_projection.requestScreenCapture({orientation:2});
    const requestB=m.media_projection.requestScreenCapture({orientation:3});
    await new Promise(setImmediate);
    check(()=>assert.equal(requests.length,1));
    requests[0].resolve(true);const capturerA=await requestA;
    await new Promise(setImmediate);
    check(()=>assert.equal(requests.length,2));
    check(()=>assert.equal(capturerA.latestImageOrNull(),null));
    requests[1].resolve(true);const capturerB=await requestB;
    check(()=>assert.equal(images.listenerCount('screen_capture'),1));
    const stops=calls.filter(call=>call[0]==='stop').length;
    capturerA.stop();
    check(()=>assert.equal(calls.filter(call=>call[0]==='stop').length,stops));
    const inactive=new m.media_projection.ScreenCapturer();inactive.stop();
    check(()=>assert.equal(calls.filter(call=>call[0]==='stop').length,stops));
    images.emit('screen_capture',new Frame(21,8));
    const finalCapture=await capturerB.nextImage();
    check(()=>assert.equal(finalCapture.width,21));
    capturerB.stop();
    check(()=>assert.equal(calls.filter(call=>call[0]==='stop').length,stops+1));
    check(()=>assert.equal(images.listenerCount('screen_capture'),0));
    images.requestScreenCaptureAsync=()=>Promise.reject(new Error('permission failed'));
    await assert.rejects(m.media_projection.requestScreenCapture(),/permission failed/); checks++;
    images.requestScreenCaptureAsync=()=>Promise.resolve(true);
    const afterFailure=await m.media_projection.requestScreenCapture();afterFailure.stop();
    check(()=>assert.equal(images.listenerCount('screen_capture'),0));
    images.requestScreenCaptureAsync=fastRequest;
    check(()=>assert.equal(intervals.size,0));
    img.recycle(); check(()=>assert.throws(()=>img.pixel(0,0),/recycled/));
    check(()=>assert.equal(imageMat.releaseCount,1));
    img.recycle(); check(()=>assert.equal(imageMat.releaseCount,1));
    check(()=>assert.throws(()=>img.mat,/recycled/));
    console.log('MODERN_COMPAT_OK checks='+checks);
})().catch(error=>{for(const id of intervals)clearInterval(id); console.error(error);process.exitCode=1;});

