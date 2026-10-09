// @engine quickjs
// 直接调用内置真实 SIFT API；支持图片路径，不需要自己提取或回收特征。
var started = Date.now();
var result = images.matchSift('./大图.png', './小图.png', {
    fast: true,
    drawMatches: './output/match_result.jpg'
});
console.log('匹配总耗时：' + (Date.now() - started) + ' ms');
console.log(JSON.stringify(result));
if (result) app.viewFile(files.path('./output/match_result.jpg'));
