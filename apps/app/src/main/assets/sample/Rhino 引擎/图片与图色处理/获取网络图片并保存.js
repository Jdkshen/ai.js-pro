"rhino";
// 网络依赖：从 httpbin 下载一张 PNG 测试图；网络不可用或请求失败会报错，不会用本地图片顶替。
var url = "https://httpbin.org/image/png";
var image = null;
try {
    image = images.load(url);
    if (!image) {
        throw new Error("images.load 返回 null，请检查网络后重试：" + url);
    }
    // 相对路径基于当前示例（导入）目录，不写死绝对路径
    var path = files.path("./downloaded-image.png");
    var saved = images.save(image, path, "png", 100);
    if (!saved || !files.exists(path)) {
        throw new Error("保存失败：" + path);
    }
    console.log("图片已保存到：" + path);
} catch (e) {
    throw new Error("获取网络图片失败：" + e);
} finally {
    if (image) image.recycle();
}