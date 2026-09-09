function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }


var sourceStartedAt = __nowMs();
var superMario = images.read("asset://sample/Rhino 引擎/图片与图色处理/找图/super_mario.jpg");
console.log("[Rhino耗时] 读取原图: " + __formatMs(__nowMs() - sourceStartedAt) + " ms");
var templateStartedAt = __nowMs();
var block = images.read("asset://sample/Rhino 引擎/图片与图色处理/找图/block.png");
console.log("[Rhino耗时] 读取模板: " + __formatMs(__nowMs() - templateStartedAt) + " ms");
var matchStartedAt = __nowMs();
var points = images.matchTemplate(superMario, block, {
    threshold: 0.8
}).points;
console.log("[Rhino耗时] matchTemplate: " + __formatMs(__nowMs() - matchStartedAt) + " ms");
console.log("[Rhino结果] 匹配数量: " + points.length);

toastLog(points);

var canvas = new Canvas(superMario);
var paint = new Paint();
paint.setColor(colors.parseColor("#2196F3"));
points.forEach(point => {
    canvas.drawRect(point.x, point.y, point.x + block.width, point.y + block.height, paint);
});
var image = canvas.toImage();
images.save(image, "/sdcard/tmp.png");

app.viewFile("/sdcard/tmp.png");

superMario.recycle();
block.recycle();
image.recycle();
