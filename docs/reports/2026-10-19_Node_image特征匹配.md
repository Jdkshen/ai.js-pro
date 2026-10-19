# Node image 特征提取/匹配（task-172）

日期：2026-10-19。执行：t1-native-services。验证机：K40（cccc62c7，dev-1121 待填）。

## 1. 问题与基线

t2 复跑 Node「特征匹配(全分辨率找图).js」失败：`image` 模块无 `matchFeatures`、`Image` 实例无 `detectAndComputeFeatures`。
基线（K40 dev-1120，`.artifacts/node172-verify/baseline-dev1120.json`）：

    TypeError: tpl.detectAndComputeFeatures is not a function

## 2. 实现（复用 Rhino/QuickJS 的 Java FeatureMatcher）

### 2.1 Node24Images（engine，kind=image 宿主）

- 新增 action：
  - `detectAndComputeFeatures`：Bitmap 句柄 → `Utils.bitmapToMat` → `FeatureMatcher.detect`（ORB-lite，与 `$images.detectAndComputeFeatures` 同实现）；支持 scale（<=0 按默认 0.5/1）、grayscale、method、region；特征对象保存在宿主，返回 `{id,count,width,height,scale,method}` 句柄；
  - `matchFeatures`：scene/object 特征句柄 → `FeatureMatcher.match`（汉明 + Lowe ratio，默认 0.7）+ `estimateFrameCorners` 四边形；场景缩放坐标还原为原图坐标，返回 `{center,topLeft,topRight,bottomRight,bottomLeft,matches,sceneWidth/Height,objectWidth/Height}`；未匹配返回 null；`drawMatches` 可选写调试图；
  - `recycleFeatures`：释放特征对象与底层 Mat；
- `close()`/任务取消统一释放所有图片与特征 Mat；
- 与 Rhino `Images.detectAndComputeFeatures/matchFeatures` 保持同一算法与结果语义。

### 2.2 node24 prelude（image 模块）

- `Image.prototype.detectAndComputeFeatures([options])`（返回 Promise）/ `detectAndComputeFeaturesSync`；
- `ImageFeatures` 包装：`id/count/width/height/scale/method` 只读属性、`recycle()/close()/isRecycled()`，重复 recycle 幂等；
- `image.matchFeatures(sceneFeatures, objectFeatures[, options])`（返回 Promise）/ `matchFeaturesSync`：选项 `thredshold`（兼容 threshold）、`drawMatches`（相对脚本目录解析）、`matcher`（接受）；返回对象含 `center`；未匹配 null；
- `image` 模块导出 `ImageFeatures/matchFeatures/matchFeaturesSync`；
- 与 Pro 示例调用形态一致：`await template.detectAndComputeFeatures()`、`await matchFeatures(sceneFeatures, objectFeatures, {drawMatches})`、`result.center`、`sceneFeatures.recycle()`。

## 3. 修改文件

- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24Images.java`；
- `modules/engine/src/main/assets/node24/prelude.js`。

编译：`:engine:compileDebugJavaWithJavac` + `:app:compileMiuixCompatDebugJavaWithJavac -PflutterPoc=true` BUILD SUCCESSFUL；`node --check prelude.js` 通过。

## 4. K40 真机验证（dev-1121 待填）

fixture（`.artifacts/node172-fixture/`）：hellokitty 模板 174x170；1080x1920 网格背景 + 模板贴 (300,600)`scene.png`；模板缩放 0.8 旋转 12° 贴 (400,900)`scene_rot.png`；无模板噪声图 `wrong.png`。

（待填：特征数、匹配中心、旋转/缩放匹配、错图 null、drawMatches 输出）

## 5. 限制

1. 算法与 Rhino 兼容后端一致（imgproc 角点 + 二进制描述子，非 OpenCV SIFT/ORB features 模块）；method=ORB/SIFT 接受但不换实现；
2. 特征对象占用宿主 Mat，必须 recycle（或任务结束时统一 close 释放）；
3. Node 与 Rhino 结果坐标都是「场景原图坐标」，不做屏幕度量缩放（Node 图片没有 screen metrics 概念）；
4. 匹配效率与 Rhino 一致（低分辨率特征更快，可传 scale）。
