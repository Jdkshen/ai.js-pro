'use strict';
const assert = require('assert');
const path = require('path');
const project = path.resolve(__dirname, '../../apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋');
const adapter = require(path.join(project, 'src/runtime/aijs-adapter.js'));
let calls = 0, taps = 0, screenshotCalls = 0, recycled = 0;
let options;
const bounds = {left:50, top:100, right:1050, bottom:2300, width:1000, height:2200};
const image = {width:1080, height:2400, recycle() { recycled++; }};
const target = {windowId:12, packageName:'game.test', bounds, active:true};
global.images = {
    isWindowCaptureAvailable: () => true,
    listCaptureWindows: () => [target],
    captureWindow: opts => {
        calls++; options = opts;
        return {image, ...target, frameId:calls, timestamp:100 + calls, isolated:true, coordinateSpace:'screen'};
    }
};
global.captureScreen = () => { screenshotCalls++; throw new Error('must not capture display'); };
global.press = () => { taps++; return true; };
global.auto = {service:{}};
adapter.setCaptureBackend('window', {persist:false});
assert.strictEqual(adapter.requestCapture(), true);
assert.strictEqual(adapter.capture('full', 2000), image);
assert.strictEqual(options.screenCoordinates, true);
assert.strictEqual(adapter.windowCaptureInfo().frameId, 1);
adapter.capture('full', 2000);
assert.strictEqual(options.packageName, 'game.test', 'subsequent frames must stay bound to the game');
const tapper = adapter.createTap({method:'accessibility'});
assert.strictEqual(tapper.tap(500, 500).ok, true);
target.active = false; target.foreground = true;
assert.strictEqual(tapper.tap(500,500).ok,true,'verified foreground app remains usable after touching our overlay');
target.foreground = false;
assert.strictEqual(tapper.tap(500,500).ok,false,'a background window must not receive a move');
adapter.resetWindowCaptureGeometryForCalibration();
target.active = true; adapter.capture('full',2000);
target.bounds = {...bounds, top:200};
assert.strictEqual(tapper.tap(500, 500).ok, false, 'window movement must reject stale move coordinates');
assert.strictEqual(taps, 2);
assert.throws(() => adapter.capture('full', 2000), /定位校准/, 'new frames must not make an old grid valid after movement');
assert.strictEqual(adapter.windowCaptureNeedsCalibration(), true);
adapter.resetWindowCaptureGeometryForCalibration();
assert.strictEqual(adapter.capture('full', 2000), image);
global.images.captureWindow = () => { throw new Error('secure window'); };
assert.throws(() => adapter.capture('full', 2000), /secure window/);
assert.strictEqual(screenshotCalls, 0, 'isolated mode must never silently fall back to full display');
global.images.captureWindow = () => ({image, isolated:false, coordinateSpace:'screen'});
assert.throws(() => adapter.capture('full', 2000), /隔离/);
assert.strictEqual(recycled, 2, 'moved and invalid returned frames must release their pixels');
global.images.isWindowCaptureAvailable = () => false;
assert.throws(() => adapter.requestCapture(), /Android 14/);
const state = {adapter, hideOnCapture:true};
const placement = require(path.join(project, 'src/ui/panel/placement.js')).create(state, {});
assert.strictEqual(placement.needHideForCapture(), false, 'window capture must not toggle overlay windows');
console.log('CHESS_WINDOW_CAPTURE OK: source binding, coordinate guard, failure isolation, cleanup, visibility');
