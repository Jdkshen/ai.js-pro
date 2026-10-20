# @autojs/opencv 评估（task-146 ④，不强行实现）

> 2026-10-20 更新：下文为历史方案评估。当前 `require('image')` 已通过 Java/OpenCV
> 实现模板/特征匹配，`ocr` 已接内置 PP-OCRv6 与 MLKit。本批为高级版原始找色
> 示例补了 `require('@autojs/opencv').Rect` 的零参数/四参数构造与只读坐标，
> 可直接传入 image API 的 region；不代表完整 OpenCV addon 已兼容，Rect 的
> and/or/pad/rescale 等方法也尚未实现。不能把下文的“本批不实现”当作当前 API 清单。

## Pro 现状（反编译依据）

- `assets/v8/built_in_modules/@autojs/opencv.js` + `node_modules/@autojs/opencv/`：JS 封装 + `prebuilt/opencv4autojs.arm.node`（1.6MB）与 `opencv4autojs.arm64.node`（3.5MB）两个 **Node 原生插件**，另有 `lib/typings/*` 大量 d.ts、haarcascade 数据。
- Pro 运行时是 Node 16（ABI 93，V8 9.4）的定制 embed，故 `.node` 插件按 Node 16 ABI 编译。
- 我们 Node24 是 Node 24.22（ABI 137），**Pro 的预编译 .node 不能在 Node24 里 require（ABI 不匹配，会报 NODE_MODULE_VERSION 137 vs 93）**。

## 可选方案

### 方案 A：重建 Node 24 addon（全量 OpenCV）

- 做法：从 Pro 的 opencv4autojs 源码/或 OpenCV Android SDK + node-addon-api，针对 Node 24 头文件与 Android NDK/arm64 交叉编译，生成 ABI 137 的 `.node`，内置到 assets，运行时抽取到 `filesDir` 并 `process.dlopen`。
- 优点：完整 OpenCV API、与 Pro 脚本兼容度最高（可直接跑 Pro 的 opencv 示例）。
- 成本/风险：需要 node-gyp 交叉编译链（Android NDK r26 + Node 24 headers + libc++_shared）、两个 ABI（arm64 必需，armv7 可选）、每 ABI 3~5MB 体积、首次实现/CI 验证成本高；与 App 现有 `libopencv_java4.so` 可能重复。
- 结论：**不建议本批强行做**，留作独立任务（需要 native-process 先搭好 Node addon 交叉编译验证）。

### 方案 B：复用 App 已内置的 OpenCV Java native（推荐先做，改造小）

- 做法：Host 侧已有 `kind:"image"`（Node24Images，Java 调 OpenCV/Android Bitmap）与 `libopencv_java4.so`；扩展 Node24Images 的 image actions（findImage/templateMatch/feature/...），JS 侧新增 `opencv`/`image` 模块包装，零 `.node`、无 ABI 问题，可覆盖脚本最常用的找图/模板匹配。
- 成本：需要 Java 宿主新增 action（`Node24Images`/`Node24HostBridge`，约 1-2 天）；JS 包装 1 天。
- 风险：OpenCV Java API 面远小于完整 addon；OCR/特征匹配等仍缺（OCR 可另接 MLKit）。

### 方案 C：纯 JS 轻量实现（不推荐）

- 用 `kind:image` 已能取像素/找色，JS 里做简单模板匹配（暴力滑窗）在 1080p 上性能不可接受（每帧数十亿次比较）；仅适合极小图/演示。

## 建议

1. 本批不实现 `@autojs/opencv`，在文档/示例里明确标注“未支持”，避免把接口存在当可用。
2. 下一批优先做 **方案 B**（Java OpenCV + image actions：findImage/templateMatch 常用面），交付后再评估是否补方案 A 的完整 addon。
3. 若产品必须 1:1 兼容 Pro 的 `@autojs/opencv` API，则安排独立 task：Node 24 addon 交叉编译 + 内置 assets + ABI 校验 + samples 回归。
