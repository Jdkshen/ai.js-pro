# 教程与示例 API 对齐说明

来源目录：`C:\Users\18101\Downloads\教程文档`

本工程的脚本运行时以 Rhino/Auto.js 4.x API 为主。下载目录同时包含新版 Auto.js Pro API、第二代引擎和 Node.js 示例，因此不能整目录复制到 APK。

## 本次接入

- `安卓7.0+点按和手势/RootAutomator双指捏合.js`
- `复杂界面/高性能列表.js`
- `复杂界面/应用浏览器.js`
- `脚本引擎/被运行的脚本文件.js`
- `任务/读取文本文件.js`

这些示例已改为当前工程实际提供的 `ui`、`threads`、`engines`、`dialogs`、`device` 和 Java 互操作 API。没有复制下载目录里的 `$ui`、`$threads`、`$engines` 等新版别名。

## 明确排除

- 第二代引擎官方示例：脚本内容使用 `"nodejs";` 入口。
- `nodejs-modules`、`node_modules`、npm、CommonJS/ESM 服务端示例。
- 当前运行时没有实现的 Auto.js Pro 专属模块：`$work_manager`、`$zip`、`$crypto`、`sqlite`、`RootAutomator2`、`$ocr`、特征匹配和新版 WebSocket API。
- 依赖未随 APK 提供的 OCR/FFmpeg 等外部插件示例。
- 依赖完整项目导入、Android Resources 或新版打包器字段的项目示例；当前示例页只支持单文件导入，直接放入会丢失资源目录。

## 同步原则

1. 已存在的同名示例以仓库版本为准，不用下载目录覆盖。
2. 新增示例必须能映射到 `modules/autojs` 中的实际 API，或改写为 Android Java API。
3. 示例资产不得出现 Node.js 入口，也不得包含 `node_modules`。
4. 需要新增运行时 API 时，先实现并测试 API，再开放对应示例。
