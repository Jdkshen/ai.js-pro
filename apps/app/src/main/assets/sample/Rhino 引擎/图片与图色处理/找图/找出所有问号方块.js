function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }


var sourceStartedAt = __nowMs();
var superMario = images.read("asset://sample/Rhino 引擎/图片与图色处理/找图/super_mario.jpg");
console.log("[Rhino耗时] 读取原图: " + __formatMs(__nowMs() - sourceStartedAt) + " ms");
var templateStartedAt = __nowMs();
var block = images.read("asset://sample/Rhino 引擎/图片与图色处理/找图/block.png");
console.log("[Rhino耗时] 读取模板: " + __formatMs(__nowMs() - templateStartedAt) + " ms");

var findStartedAt = __nowMs();
var first = images.findImage(superMario, block, {threshold: 0.8});
console.log("[Rhino耗时] findImage: " + __formatMs(__nowMs() - findStartedAt) + " ms");
toastLog("第一个匹配: " + first);

var matchStartedAt = __nowMs();
var result = images.matchTemplate(superMario, block, {
    threshold: 0.8
}).matches;
console.log("[Rhino耗时] matchTemplate: " + __formatMs(__nowMs() - matchStartedAt) + " ms");
console.log("[Rhino结果] 匹配数量: " + result.length);
toastLog(result);

superMario.recycle();
block.recycle();
