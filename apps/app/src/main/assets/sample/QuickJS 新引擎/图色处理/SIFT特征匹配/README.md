# SIFT 特征匹配

QuickJS、Rhino 共用内置真实 SIFT + FLANN + RANSAC API，不需要辅助模块。

## 运行

运行 `main.js`（已启用 fast），大图、小图已附带，无需网络、截图或无障碍权限。结果图输出到 `output/match_result.jpg` 并自动打开。导出时保留整个图片目录。日志会单独显示匹配总耗时（含读取图片、准备模板和可选绘图，不含日志打印及打开图片）；缓存示例分别显示模板准备耗时和每次匹配耗时，单位为毫秒。

## 单次匹配

```javascript
var result = images.matchSift('./大图.png', './小图.png');
if (result) console.log(result.centerX, result.centerY);
```

输入支持本地路径、images.read() 或截图返回的图片。没有可靠匹配返回 null；错误路径、参数或写图失败会抛错。

## 重复匹配

```javascript
var matcher = images.createSiftMatcher('./小图.png', {fast: true, track: true, scale: 0.5});
try {
    var result = matcher.match(frame);
} finally {
    matcher.recycle();
}
```

循环时在循环外创建 matcher。模板特征只计算一次，`track: true` 首次全图搜索，后续先搜上次位置，失败后自动回到全图。显式 region 优先；图像尺寸变化时重新全图搜索。`缓存模板重复匹配.js` 提供可直接运行的示例。

matcher 创建后可回收模板源图；场景图片仍由调用者回收。close() 等同 recycle()，允许重复调用，脚本退出也会清理缓存。传图片对象可省去重复文件解码；正式循环省略 drawMatches 可以减少绘图与写盘。

## 参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| scale | 超过100万像素为0.5，否则1 | 场景缩放，范围0.1～4，按搜索区域面积计算默认值 |
| templateScale | 1 | 模板缩放，范围0.1～4；缓存创建时确定 |
| maxFeatures / templateMaxFeatures | 0 | 特征上限，0表示不限制，最大10000；模板上限在缓存创建时确定 |
| region | 全图 | [x,y,width,height]，原图内整数区域 |
| track / trackPadding | false / 64 | 优先跟踪上次位置；扩展原图像素0～2048。padding≥16时搜索框向外对齐16像素，稳定亚像素抖动 |
| fast | false | 大区域先按请求scale的2/3提取特征；至少8个可靠内点才接受，否则回到请求scale。更小的目标可能增加一次失败尝试 |
| reuseScene | true | 对实际缩放灰度输入逐字节比较，像素和特征参数一致时复用最近一次场景特征及FLANN索引；false强制重算 |
| threshold | 0.7 | Lowe距离比率，0.01～0.99；不是相似度 |
| minMatches | 6 | 最少可靠内点，至少4；跟踪局部至少8 |
| ransacThreshold | 3 | 缩放后场景像素的重投影阈值 |
| minAngle / maxAngle | 30 / 150 | 四边形角度范围，单位度 |
| drawMatches | 不输出 | 匹配调试图输出路径；未匹配时不写图 |
| grayscale | false | true输出灰度调试图；SIFT始终在灰度图计算 |

`matcher.match(scene, options)` 覆盖创建时的搜索参数。模板参数只在创建时使用。

结果包括四角、corners、angles、center、centerX/centerY、matchCount（可靠内点）、candidateCount、场景/模板特征数、method: 'SIFT'、searchMode（full/region/tracked）和 elapsedMs。

坐标已还原到场景原图，包括 ROI 偏移和奇数尺寸缩放。elapsedMs 包括该帧搜索及可选绘图，不包括缓存模板准备；测量单次 API 总耗时使用 Date.now()。用于点击前确认图片与屏幕坐标一致。

限制 maxFeatures 过低可能漏匹配，因此默认不限制。旧 images.detectAndComputeFeatures()/matchFeatures() 保留兼容后端；真实 SIFT 使用新的 matchSift()/createSiftMatcher()。OpenCV features 的发布与实测范围为 arm64-v8a。

## C++ 后端与速度

特征提取、FLANN、比率过滤、RANSAC、几何校验和绘图全部在 C++ 完成。QuickJS 直接借用原生帧缓冲区，不再转成 Java Mat；调用期间保留 shared_ptr，防止帧被回收后复用。Rhino 借用 Mat，调用期间由 JNI 保持 Java 对象存活。临时帧令牌在 finally 释放，不积累 Java/JS 引用。

缓存示例已改为先读取大图，循环内传图片对象，显式 scale:0.5，不绘图。首次先以低分辨率搜索全图，失败回到请求分辨率；后续跟踪失败会全图回退。SIFT 的全图计算仍然较贵，不能只靠下沉 C++ 就把任意全图搜索变为几毫秒。

结果新增 backend:'cpp'、inputMode（frame/mat/path）、templatePrepareMs 和 timings：inputMs（读取/借用图像，不含截图）、featureMs、flannMs、geometryMs、drawMs。缓存对象也提供 templatePrepareMs。模板准备时间是创建时的一次性数据，不计入每帧 timings；elapsedMs 是该帧原生总耗时，脚本日志显示整个 API 调用耗时。

dev-770 在 K40 上的预读图片跟踪中位数为 QuickJS 9ms、Rhino 13ms。此次继续减少重复计算：场景特征和 FLANN 索引可以缓存；fast 可先尝试更低分辨率。当前测量与限制见源码文档的 dev-771 报告。文件路径仍需图片解码，实时循环传图片对象。

## 减少重复计算

`reuseScene` 默认开启，每个 matcher 只缓存最近一次场景灰度、特征及索引，随 matcher 回收。缓存按实际搜索区域和缩放后的灰度逐字节比较，不根据图片句柄或抽样判断。同一对象被修改也会失效；搜索区域以外的变化不会影响区域特征。SIFT 特征参数变化会重算。

命中缓存后仍执行最近邻查询、阈值过滤和 RANSAC，不直接返回上次点击坐标。匹配失败会丢弃近似 FLANN 索引，避免某次随机索引导致后续一直漏匹配。区域位置改变时重新还原坐标。调试图使用本次画面颜色。

结果新增 `featureCacheHit`、`indexCacheHit`、`searchScale`（本次实际横向缩放）、`searchPasses` 和 `timings.cacheCheckMs`。后者是精确像素比较耗时，独立于 featureMs；不同搜索尝试的耗时相加。`fast:true` 先搜索的 scale 是请求 scale 的2/3，处理后的区域超过25万像素且请求 scale≥0.3时才启用；至少8个可靠内点才接受，否则回到请求 scale。首次仍须计算 SIFT，小目标和模糊目标可能需要回退，失败尝试会增加耗时。

如需每帧完整重算，使用 `{fast:false,reuseScene:false}`；若已知目标位置，优先设置 region 或开启 track。低分辨率成功时可靠点数量可能少于完整搜索。示例日志显示是否复用特征，不能把静止画面的缓存速度当作每帧都变化时的速度。
