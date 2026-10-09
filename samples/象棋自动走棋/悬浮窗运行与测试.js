// @engine quickjs
"use strict";

const startedAt = Date.now();

// 宿主 execScriptFile 的工作目录可能为 /，入口必须以自身目录加载。
const baseDir = (typeof __dirname === "string" && __dirname && __dirname !== ".") ? __dirname : ".";
const selfName = (typeof __filename === "string" && __filename)
    ? String(__filename).split(/[\\/]/).pop() : "悬浮窗运行与测试.js";
const singleton = require(baseDir + "/src/app/panel-singleton.js");
const admitted = singleton.admit(engines, files, selfName);
if (admitted.ok) {
    require(baseDir + "/src/app/panel-app.js").run({ baseDir: baseDir, selfName: selfName,
        startedAt: startedAt, singleton: singleton });
} else {
    console.log("[floaty] " + admitted.reason);
}
