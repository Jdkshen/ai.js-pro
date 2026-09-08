function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }


var sourceStartedAt = __nowMs();
var superMario = images.read("./super_mario.jpg");
console.log("[Rhino耗时] 读取原图: " + __formatMs(__nowMs() - sourceStartedAt) + " ms");
var templateStartedAt = __nowMs();
var mario = images.read("./mario.png");
console.log("[Rhino耗时] 读取模板: " + __formatMs(__nowMs() - templateStartedAt) + " ms");
var findStartedAt = __nowMs();
var point = findImage(superMario, mario);
console.log("[Rhino耗时] findImage: " + __formatMs(__nowMs() - findStartedAt) + " ms");
toastLog(point);

superMario.recycle();
mario.recycle();
