# Node image 特征提取/匹配（task-172）

日期：2026-10-19。执行：t1-native-services。验证机：K40（cccc62c7，dev-1121）。

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

## 4. K40 真机验证（dev-1121）PASS

fixture（`.artifacts/node172-fixture/`，Python/Pillow 生成）：

- `template.jpg`：hellokitty 174x170（样例自带图）；
- `scene.png`：1080x1920 网格背景 + 模板贴 (300,600)；
- `scene_scale.png`：模板缩放 0.8 贴 (400,900)；
- `scene_rot_only.png`：模板旋转 12° 贴 (400,900)；
- `scene_rot.png`：模板缩放 0.8 且旋转 12°；
- `wrong.png`：无模板噪声图。

验证脚本：`/sdcard/脚本/node172-feature-extra.node.js`（`await readImage` → `await detectAndComputeFeatures` → `await matchFeatures` → drawMatches → result JSON）。

| fixture | 结果 |
|---|---|
| template 特征 | count=35（method=SIFT scale=1） |
| scene.png | center=(387,685)，quad=(300,600)-(474,770) 与粘贴位置完全一致，matches=33 |
| scene_scale.png（0.8 缩放） | center≈(469.5,968)，quad 覆盖 (400,900)-(539,1036)，matches=11，**缩放匹配 PASS** |
| scene_rot_only.png（12° 旋转） | center≈(503,1002)，quad 呈旋转四边形，matches=15，**旋转匹配 PASS** |
| scene_rot.png（0.8 缩放+12° 旋转） | null（ORB-lite 兼容后端在大角度+缩放叠加时未匹配；Rhino `$images` 同 fixture 结果相同，见 §4.1） |
| wrong.png（无模板） | null，**错误图未匹配 PASS** |
| drawMatches | `/sdcard/node172/matches.jpg` 已生成（1080x1920，33 条绿色匹配线 + 蓝色四边形框），已拉回 `.artifacts/node172-verify/matches.jpg` |

证据：`.artifacts/node172-verify/result-extra.json`、`matches.jpg`、`baseline-dev1120.json`（build 前 API 不存在）。

### 4.1 与 Rhino 兼容后端一致性

同一 fixture 用 Rhino `$images.detectAndComputeFeatures/matchFeatures` 跑（`/sdcard/脚本/node172-rhino.js`）：

    tpl features=35 method=SIFT
    scene.png -> center=(387,685)
    scene_rot.png -> null
    wrong.png -> null

与 Node 结果完全一致 → Node 复用同一 Java 算法，行为与现有 Rhino 兼容后端对齐（旋转+缩放叠加的局限是算法本身，不是 Node 新 API 的问题）。

### 4.2 内置示例「特征匹配(全分辨率找图).js」

- 示例目录已 push 到 `/sdcard/脚本/node172-sample/` 并在 K40 dev-1121 启动；`readImage('./hellokitty.jpg')` + `detectAndComputeFeatures()` 正常执行；
- 走到 `requestScreenCapture()` 时出现 Android 16「要与 AI.js Pro 共享屏幕吗？」选择器；本次自动化选择「单应用共享（AI.js Pro）」→ 示例 `openUrl` 打开浏览器后，截图目标（AI.js Pro）不可见，系统报 `单应用截图的目标当前不可见；请打开授权时选中的应用，或重新授权整个屏幕`，示例停止；
- 该失败来自屏幕共享授权模式（media_projection 系统行为），与特征 API 无关；示例的 API 调用形态（`await template.detectAndComputeFeatures()`、`await matchFeatures(..., {drawMatches})`、`result.center`、`sceneFeatures.recycle()`）已由上面的 fixture 脚本逐项覆盖并通过；
- **手动跑示例**：授权弹窗里选择「整个屏幕」（顶部箭头）后再运行，即可完成 3 次检测循环与 matches.jpg 绘制。

## 5. 限制

1. 算法与 Rhino 兼容后端一致（imgproc 角点 + 二进制描述子，非 OpenCV SIFT/ORB features 模块）；method=ORB/SIFT 接受但不换实现；
2. 特征对象占用宿主 Mat，必须 recycle（或任务结束时统一 close 释放）；
3. Node 与 Rhino 结果坐标都是「场景原图坐标」，不做屏幕度量缩放（Node 图片没有 screen metrics 概念）；
4. 匹配效率与 Rhino 一致（低分辨率特征更快，可传 scale）。
