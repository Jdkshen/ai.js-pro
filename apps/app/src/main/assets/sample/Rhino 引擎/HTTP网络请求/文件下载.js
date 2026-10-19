"rhino";
// 保存到当前示例目录；请求失败时不写文件、不误报下载成功。
var url = "https://httpbin.org/image/png";
var dest = files.path("./downloaded-image.png");
var res = http.get(url);
if (res.statusCode !== 200) {
    throw new Error("下载失败，HTTP " + res.statusCode);
}
files.writeBytes(dest, res.body.bytes());
console.log("下载成功：" + dest);
app.viewFile(dest);
