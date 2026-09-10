//项目仓库内的 AI.js Pro 图标（需联网，GitHub raw 链接）
var url = "https://github.com/Jdkshen/ai.js-pro/raw/HEAD/modules/autojs/src/main/res/drawable-nodpi/ai_js_pro_logo.png";
var res = http.get(url);
if(res.statusCode != 200){
    toast("请求失败");
}
files.writeBytes("/sdcard/1.png", res.body.bytes());
toast("下载成功");
app.viewFile("/sdcard/1.png");