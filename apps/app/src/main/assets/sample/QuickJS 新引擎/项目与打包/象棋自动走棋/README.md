# 象棋自动走棋 · QuickJS 工程骨架

中国象棋自动走棋工程的**落点**：FEN / 棋盘几何 / 格点分配 / 时序融合已经是真代码（有离线断言），
但识别链路还没有模型，棋规、算棋、落子尚未实现。界面上的状态文案与这里保持一致，
不会把"还没做"写成"已经能用"。

## 现在有什么

- `project.json`：QuickJS 工程（`engine: quickjs`、`uiMode: true`、保留 OpenCV 模块）。
- `main.js`：界面骨架 + 两个入口：「运行离线自检」「查看运行环境」（都不点击棋盘）。
- `src/chess/fen.js`：FEN 生成 / 解析 / 静态校验（不设少子残局门槛，未知轮次不塞 side）。
- `src/vision/geometry.js`：规范交点 ↔ 屏幕坐标、180° 方向映射、稳健拟合（含离群点剔除）。
- `src/vision/cell-assignment.js`：检测框 → 90 个交点，保留候选/冲突/残差，不硬猜。
- `src/vision/temporal-fusion.js`：有时间跨度的多帧融合，版本变化即作废历史。
- `src/runtime/aijs-adapter.js`：**唯一**直接调用宿主接口的地方，能力缺失如实上报。
- `tools/self-check.js` 离线自检；`tools/runtime-probe.js` P0 运行时探针。
- `assets/models/`：模型槽位（空），见其中的 `README.md`。

## 目录结构

```
主界面与探针：  main.js、tools/self-check.js、tools/runtime-probe.js
可离线验证：    src/chess/（FEN、静态校验）、src/vision/（几何、格点分配、时序融合）
平台接口：      src/runtime/aijs-adapter.js（业务代码只用它，不直接调宿主）
还没做：        src/chess/rules.js、legal-moves.js、transition.js
                src/engine/（UCI 客户端与本地引擎）、src/automation/（状态机、票据、点击、确认）
                src/ui/、assets/models/ 里的模型、assets/book/（可选本地开局库）
```

自检怎么跑：PC 上 `node tools/self-check.js`；手机上打开工程点「运行离线自检」（不截图、不点击）。

⚠️ 放进这个示例目录的**每个 `.js` 首行都必须是 `// @engine quickjs`**（`:app:verifyQuickJsSamplesMarked`
在每次构建时校验整棵 `QuickJS 新引擎/`），模块文件也不例外。

## 硬性约束

- **完全本地**：不做云库、远程推理、远程棋力；不添加 `INTERNET` 权限。资源随包提供，断网可用。
- **权限**：本工程在 `permissionConfig.manifestPermissions` 里明确声明 5 条
  （存储读写 / 悬浮窗 / WakeLock / 震动），**不含** `INTERNET`、`ACCESS_NETWORK_STATE`。
  打包页以工程声明为准；模板自带的那 16 条只在工程没写这段时才是默认值。
- **模型契约**：`yolo.load({ backend: 'dnn' })` 走 OpenCV DNN，当前只适配固定输入、六列输出
  （`[x1,y1,x2,y2,score,classId]`）的 YOLO26 类模型；非六列输出必须先按 `decoder` 显式解码，
  不能靠"总元素能除以 6"猜。
- **不要用 COCO 模型冒充象棋模型**：内置的 `yolo26_160/640.onnx` 是通用目标模型，能加载不等于能识别红车黑炮。
- **点击前置条件**：轮次未知、局面不可靠、票据过期时一律不许点击（详见任务书的状态机一节）。

## 导入与打包

1. 在 AI.js Pro 的**资源页**点这个工程条目的下载按钮，整个工程会落到 `脚本目录/下载资源/…`；
   也可以在**示例页**里直接进入目录后再"打包（项目）"。
2. 打包页选择 `project.json` 所在目录作为源，按项目模式打包；保持"移除 OpenCV"关闭。
3. 包名 `com.jdkshen.xiangqi.quickjs`，当前不含任何网络权限。

## 验收现状

- ✅ 工程能导入、能打开、能进打包页；独立 APK 可启动（K40 实测）
- ✅ 离线自检：28 条断言全过（Node 上跑；QuickJS 上待复跑）
- ❌ 识别（无模型）/ 棋规 / 算棋 / 落子：**未实现**，因此也**没有**任何准确率、FPS 或真机成绩
