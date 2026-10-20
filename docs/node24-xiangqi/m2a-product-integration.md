# M2a：QuickJS 面板 ↔ Node24 识别后端（产品集成第一步）

产物：正式象棋工程内「识别一帧 → Node24 识别 → 面板显示 FEN」全链路；以及自动走棋实跑的回归证据。
**结论：PASS**（面板全流程 + 同帧 parity + 自动走棋实跑，2026-10-20 真机 K40 `cccc62c7`，dev 构建 1.0.2 / 工程 0.6.82）。

## 1. 接入方式（正式工程内）
| 文件 | 作用 |
|---|---|
| `node24识别.node.js` | Node24 入口（首行 `"nodejs";`）：读 `.runtime/node24-vision/request.json` → 加载 shim + `experiments/vision/recognize-board.js` → 写 `result.json`，打 `NODE24_XIANGQI_DONE` |
| `src/node24/shim.node.js` | Node24 兼容层（QuickJS 风格帧对象 / 全局 files/images/engines/yolo 等） |
| `src/app/panel/node24-bridge.js` | QuickJS 侧桥：写 request → **Route B `engines.execScriptFile`** 首选（携带 exec）→ ready/result 轮询 → 超时 `forceStop()` → Route A（`RunIntentActivity` intent）兜底 |
| `src/app/panel/calibration.js` | 「识别一帧」优先 Node24，失败回退 QuickJS；日志输出「Node24 局面 <FEN>」 |
| `tools/node24-bridge-verify.js` | 桥自检脚本（真机 RunIntentActivity 驱动） |

协议 `m2a/1`：`request.json`（image/output/sessionId）、`ready-<sid>.json`、`result-<sid>.json`；临时文件 + move 原子写。

## 2. 真机数据（正式工程，f01 帧）
- request → ready：**161ms**（Route B 启动 Node24）
- Node24 识别：**436–668ms**（同帧两次实测），面板总链路 **1163ms**
- FEN：`rnbakabnR/9/1C5C1/P1P1?1P1P/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR`，**31 子 / 1 未知 / 58 空**，located=canny-hough，detectionCount=34
- 面板日志：`[floaty] Node24 识别：子 31 空 58 未知 1｜1163ms` / `[floaty] Node24 局面 <FEN>`

## 3. 同帧 parity（Node24 vs QuickJS）
同一帧 `frame-m2a-1792461760322.png`：
- Node24：668ms；QuickJS：375ms
- **FEN 完全相同**、逐格一致、31 子 / 1 未知一致 → 后端结果可信（与 R5 的 90/90 结论一致；仅耗时不同，Node24 含模块加载）

## 4. 自动走棋实跑（回归）
- 前 6 步 **6/6 CONFIRMED**；每步 2 次 `accessibility-press` 手势（~353–359ms）点击起点/终点
- 第 4 步 **Pikafish 深度 23 / 1812ms**（inline 算棋；auto 预算首搜 1800ms）
- 面板显示「无障碍 就绪 / 运行中 / 本帧 188ms / 未知格 0」；后续至 38 次 CONFIRMED 后由 Lead 停止

## 5. 本轮修复（4 项）
1. `src/app/panel/start-flow.js`：`String(error.message || error)` 对 null 抛错 → 空值守卫（否则「开始」失败时后台崩溃）
2. `tools/build-aj-project.cjs`：**首行 `"nodejs";` 的脚本与 `src/node24/**` 原样拷贝**（此前被 Babel 降级并前置 `// @engine quickjs` + 注入 QuickJS 专用 `files/__dirname` 前导 → `Cannot find module: fs` / `files is not defined`）
3. `src/app/panel/node24-bridge.js`：`files.ensureDir(state.BASE_DIR + "/.runtime/"); files.ensureDir(workDir + "/")`（否则 `images.save` 报 Unable to open image output）
4. 设备 `autorun.computeMode=inline` 绕过 worker 路径 bug（见遗留）

## 6. 已知遗留
- **worker 算棋路径 bug**：嵌套工程路径下 worker 报 `Cannot find module: <baseDir>/../../src/runtime/task-service.js`；inline 可用（已另立任务修复）
- **投屏授权**：Android 15 每次进程重启需重新授权（单应用共享需目标应用可见；整屏共享可避开该限制但会截到悬浮面板）

## 7. 交付物
- 工程包：`.artifacts/xiangqi-aj-node-075/xiangqi-aj-node-0.6.82-node24.zip`（69,252,190 B；sha256 `d9e54905…3ce2a04`；255 条目；含 worker 路径修复）
- 校验：`release-082-node24-check.json`（`node24识别.node.js` 首行正确、shim 与源逐字节相同、forward slash、哈希与 build manifest 一致）
- 验证报告：`output/three-engine-bench/m2a-final-verification.md/.json` + `m2a-ui-device/` 证据（21 件）