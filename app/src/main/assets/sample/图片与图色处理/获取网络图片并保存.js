function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

//这个是AI.js Pro图标的地址
var url = "https://www.autojs.org/assets/uploads/profile/3-profileavatar.png";
var loadStartedAt = __nowMs();
var logo = images.load(url);
console.log("[Rhino耗时] 下载并解码网络图片: " + __formatMs(__nowMs() - loadStartedAt) + " ms");
//保存到路径/sdcard/ai-js-pro.png
var saveStartedAt = __nowMs();
images.save(logo, "/sdcard/ai-js-pro.png");
console.log("[Rhino耗时] 保存网络图片: " + __formatMs(__nowMs() - saveStartedAt) + " ms");
logo.recycle();
