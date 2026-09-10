function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

//项目仓库内的 AI.js Pro 图标地址（需联网，GitHub raw 链接）
var url = "https://github.com/Jdkshen/ai.js-pro/raw/HEAD/modules/engine/src/main/res/drawable-nodpi/ai_js_pro_logo.png";
var loadStartedAt = __nowMs();
var logo = images.load(url);
console.log("[Rhino耗时] 下载并解码网络图片: " + __formatMs(__nowMs() - loadStartedAt) + " ms");
//保存到路径/sdcard/ai-js-pro.png
var saveStartedAt = __nowMs();
images.save(logo, "/sdcard/ai-js-pro.png");
console.log("[Rhino耗时] 保存网络图片: " + __formatMs(__nowMs() - saveStartedAt) + " ms");
logo.recycle();
