'use strict';
const assert = require('assert');
const path = require('path');
const root = path.resolve(__dirname, '../../apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋');
const driverModule = require(path.join(root, 'src/automation/auto-driver.js'));
const fen = require(path.join(root, 'src/chess/fen.js'));
const renderer = require(path.join(root, 'src/ui/render-state.js'));
const layout = 'rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR';
const board = fen.parseFen(layout + ' w - - 0 1').board;
const grid = {originX:50,originY:200,stepX:100,stepY:90,version:1,orientation:'RED_BOTTOM'};
for (const allowClick of [false,true]) {
    let now = 1000;
    const events = [];
    const driver = driverModule.createDriver({
        allowClick, nowMono:() => now, sleep(){},
        locate:() => ({ok:true,grid}),
        recognize:() => ({ok:true,chars:board.slice(),layout,unknownCells:0,conflicts:0,grid}),
        analyze:() => ({ok:true,iccs:'c3c4',score:10,depth:10,ms:20,pvIccs:['c3c4','g6g5']}),
        screen:() => ({width:1080,height:2400}),
        tap:(x,y,options) => {events.push({kind:'tap',phase:options.kind}); return {ok:true};},
        onMovePlan:plan => {
            const view = renderer.build({...plan,grid,running:true,nowMono:now});
            events.push({kind:'plan',hasMove:view.hasMove,move:view.bestMove,reply:view.opponentMove});
        },
        config:{movetimeMs:20,observeFrames:1,bookOwnMoves:0,computeMode:'inline',prethink:false,winSeek:false}
    });
    driver.start(); driver.tick(); driver.assertTurn();
    for (let i=0;i<4 && !events.length;i++) {now+=100;driver.tick();}
    if (allowClick) {
        assert.ok(events.length, 'automatic search must publish a plan: ' + driver.machine.reason());
        assert.deepStrictEqual(events[0], {kind:'plan',hasMove:true,move:'c3c4',reply:'g6g5'});
        assert.deepStrictEqual(events.slice(1,3), [{kind:'tap',phase:'from'},{kind:'tap',phase:'to'}],
            'valid visual plan must precede both actual executor taps');
    } else {
        assert.strictEqual(events.length, 0, 'advice neither draws nor clicks');
    }
    driver.stop();
}
console.log('CHESS_MOVE_PLAN OK: advice without drawing/clicks; own and opponent arrows before first automatic tap');
