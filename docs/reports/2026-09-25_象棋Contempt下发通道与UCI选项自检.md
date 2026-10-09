# 象棋 Contempt 下发通道与 UCI 选项自检（0.6.26）· 2026-09-25

回答的是「开 setoption / 升级权重」两件事的实际接法：前者做掉，后者核出**没有可升级的官方权重**。

## 背景与事实核对

| 断言 | 核对结果 |
|---|---|
| 「Contempt 需要打开 setoption」 | 引擎侧**早已注册**（源码补丁 + 手机 .so 里以立即数构造选项名、`OptionsMap::add` 有静态证据）；缺的是**手机链路的下发通道**——`pikafish_jni.cpp` 只发 Threads / Hash / MultiPV，全仓 `Contempt` 只在 docs 里 |
| 「字符串被 clang 吃掉」 | 部分为误报：`.so` 文本扫描看不到 `Contemp` 是因为 AArch64 clang 把常量串编成**立即数**；`.o` 里可见的那份在 `.debug_str`（strip 后消失）。验证要用反汇编或运行时自检 |
| 「升级引擎权重能结构性超过对手」 | 本地 `pikafish.nnue`（50,706,378B，sha256 `7d13d735…`）与上游 **master-net**（2026-09-06）发布摘要**完全一致**——已是最新，无可升级；且我们用的权重本来就比对手（34MB 旧网）新 |

## 改动（四层 + 工程侧）

1. `modules/engine/src/main/cpp/pikafish/pikafish_jni.cpp`
   - 会话新增 `contempt`（-200..200 夹紧）与 `options`（启动时抓的 `option name ...` 行）；
   - `startSession` 增参：会话复用时按变化发 `setoption name Contempt`（等 readyok）；新建会话时仅当非 0 才发（保持既有启动序列）。
2. `PikafishNativeBridge.java`：`nativeStart(…, int contempt)`；新增 `nativeOptions()`。
3. `QuickJsHostBridge.java`：`pikafishStart(…, contempt)`；start 结果新增 `contempt` 与 `options` 数组（旧 .so 用 try/catch 兜底为空）。
4. `quickjs_jni.cpp`：`__aiNativePikafishStart` 解析第 4 参（注册 3→4）；bootstrap `$pikafish.start({..., contempt})` 归一化。
5. 工程侧：`src/engine/pikafish-engine.js` 增 `contempt`（DEFAULTS=**40**、`configure` 可覆盖、`normalizeContempt` 纯函数）；`悬浮窗运行与测试.js` autorun `"pikafish":{"contempt":N}` 透传；`tools/pikafish-probe.js` 打印 UCI 选项清单并点名 Contempt；`tools/self-check.js` +2 用例。

向后兼容：旧宿主 + 新 JS → 第 4 参被忽略（`options`/`contempt` 出现在结果里但为旧语义，可一眼看出）；新宿主 + 旧 JS → contempt=0，行为与之前完全一致。

## 验证（本机已完成）

- `node tools/self-check.js`：**331/331**（较 311 增加 20 条断言）
- `node tools/run-regress.js`：场景 **16/16**，legacy 失败 0
- `build-pikafish.ps1` + `build-quickjs.ps1` 重编成功；`llvm-nm -D libpikafish.so` 含 `nativeOptions`
- `:app:compileMiuixCompatDebugJavaWithJavac`：**BUILD SUCCESSFUL**（含 preBuild 的原生新鲜度等校验）

## 真机验证与发版结果（已完成）

- **dev-696**（Contempt 通道 + DotProd）发布；K40 装机跑 `tools/pikafish-probe.js`：UCI 清单 12 条、含
  `option name Contempt type spin default 0 min -200 max 200`、`configured contempt=40`，开局/中局搜索正常。
- **DotProd A/B（K40，同一探针）**：nps —— ON 两轮 165k~245k，OFF 一轮 143k~162k；深度差在 ±1~2 层噪声内
  （30s 中局 OFF 反而 24 层）。结论：**保留 DotProd**（功能无回归、nps 不劣）；老于 A76 的机型不适配
  （K40=骁龙 870 支持；新手机 OTA 后自查 `asimddp` 即可）。
- **修复：会话重建后 `options` 为空**。根因：上游 `OptionsMap::add` 的 `insert_order` 是函数级 static，
  跨实例累计 —— 同一进程第 2 个引擎的选项 idx 从 12 起，而 `operator<<` 按 idx 0..size-1 匹配 ⇒ 打印为空
  （`setoption` 按名字查，不受影响）。修复：JNI 维护**进程级选项缓存**，缓存也空时才在两次搜索之间
  向引擎重问一次 `uci`。K40 复测（start → reuse → quit 后 start）三态均返回 12 条。
- `tools/pikafish-probe.js` 修正：显式权重路径不存在时传 `null`（宿主才会回落默认目录 / 从 APK 资产提取）。
- **dev-697** 发布（含修复；versionCode 697、SHA `86645301…`、手机拉取 HTTP 200）。

## 仍待办

- 用 40 / 80 两档各打若干局，观察「不变着作和 / 循环」是否下降（预期有限：PC 对照实验里 0/40/80/120 选着变化很小）；
- 新手机 OTA 更新后跑一次 `pikafish-probe.js`（确认 dotprod 兼容 + Contempt 在位）。

## 回退

- 只把工程侧 `contempt` 置 0（或 autorun 传 `contempt:0`）即恢复旧行为；引擎默认 0，旧宿主本来就忽略该参数。
