// @engine quickjs
"use strict";
const assert = require("assert");
const guard = require("../src/app/panel-singleton.js");
const name = "悬浮窗运行与测试.js";
const state = {};
const files = {
    getSdcardPath: function () { return "/sdcard"; },
    join: function (a,b) { return a+"/"+b; },
    read: function (p) { return state[p] || ""; },
    write: function (p,v) { state[p]=v; }
};
const first={id:10},second={id:11};
const engines={current:first,myEngine:function(){return this.current;}};
assert(guard.admit(engines,files,name,1000).ok,"single panel should open");
engines.current=second;
assert(!guard.admit(engines,files,name,1001).ok,"direct duplicate must be refused");
engines.current=first;
assert(guard.touch(engines,files,2000),"owner heartbeat refreshes lease");
engines.current=second;
assert(!guard.admit(engines,files,name,2001).ok,"live owner must block duplicates");
engines.current=first;
assert(guard.beginHandoff(engines,files,name,2001),"reload should issue a handoff ticket");
engines.current=second;
assert(guard.admit(engines,files,name,2002).handoff,"ticket lets replacement start");
engines.current=first;
guard.release(engines,files);
assert(!guard.touch(engines,files,2003),"old instance must see ownership lost");
engines.current=second;
assert(!guard.admit({myEngine:function(){return {id:12};}},files,name,2004).ok,"third panel must be refused");
guard.release(engines,files);
assert(guard.admit({myEngine:function(){return {id:12};}},files,name,2005).ok,"new launch after clean exit works");
console.log("PANEL SINGLETON OK: duplicate refused, lease renewed, reload handoff, old exit, clean restart");
