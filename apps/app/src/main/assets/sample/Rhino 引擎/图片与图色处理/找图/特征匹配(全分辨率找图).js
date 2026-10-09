// @engine rhino
// 全分辨率真实 SIFT：使用随包鸡种图片，输出彩色匹配框与连线。
var started = Date.now();
var result = images.matchSift('./SIFT特征匹配/大图.png', './SIFT特征匹配/小图.png', {
    scale: 1, drawMatches: './output/feature_match.jpg'
});
console.log('匹配总耗时：' + (Date.now() - started) + ' ms');
console.log(JSON.stringify(result));
if (result) app.viewFile(files.path('./output/feature_match.jpg'));
