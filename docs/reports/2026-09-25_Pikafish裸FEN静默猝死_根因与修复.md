# Pikafish 裸 FEN 静默猝死（2026-09-25）

**状态**：双端修复已落地 + 离线验证通过；**待重编 APK 真机复验**（Java 与 native 都改了）。

改动文件（仓库根起）：

- `modules/engine/src/main/java/com/stardust/autojs/engine/QuickJsHostBridge.java`
- `modules/engine/src/main/cpp/pikafish/pikafish_jni.cpp`

## 现象

在做「QuickJS Pikafish 象棋引擎」示例的真机验证时发现：K40 上按下面这样调用（**FEN 只给棋盘段**）：

```js
$pikafish.analyze("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR", 800, 16000);
```

App 进程**静默消失**：

- 三次复现（12:59 / 13:00 / 13:03）；
- 无墓碑（tombstone）、无 ANR、无 FATAL、dropbox 无记录；
- 不是 JS 异常（脚本侧 `try/catch` 接不到），是进程级死亡 —— 脚本作者没有任何自查手段。

## 最小对照：唯一变量 = ` w - - 0 1`

| 调用 | 结果 |
| --- | --- |
| `analyze(<裸 FEN>, 800, 16000)` | 进程静默猝死（×3） |
| `analyze(<同一棋盘> + " w - - 0 1", 800, 16000)` | 正常返回（同一参数、同一时刻） |
| `analyzePosition("position fen <完整 FEN>", …)` | 正常返回 |
| `start({threads, hashMb})` 预热 | 正常 |

把「棋盘段之外的后五段（走棋方 / 易位 / 过路兵 / 半回合 / 回合数）」拿掉就必死 —— 变量收敛到一处。

## 根因与定性

- 不完整 FEN 被原样送进 native 引擎后按畸形局面解析，**进程在 native 里被带走**；
- native 侧没有留下任何崩溃栈（连墓碑都没有），**无法定位到具体代码行** —— 因此本次不追求「改到那一行」，
  直接做**入口防御**：非法输入在进入引擎之前就拦下，不给它解析的机会；
- 危险点在「静默」：正常出错应该只是一个 `error` 返回值；实际是整个 App 死掉、日志全空。
  所以要修在**宿主**（双层），而不是在示例/脚本里绕。

## 修复（双端）

### 1. Java 侧：`QuickJsHostBridge.java`

- 新增形状正则（只查形状，不查每行宽度）：

  ```text
  ^(?:[rnbakcpRNBAKCP1-9]+/){9}[rnbakcpRNBAKCP1-9]+ [wb] - - [0-9]+ [1-9][0-9]*$
  ```

  常量名 `PIKAFISH_FEN`，javadoc 里写清事故背景（「只给棋盘段会把整个 App 进程静默带走」）。
- `pikafishAnalyze` / `pikafishAnalyzePosition` 的公共入口 `pikafishAnalyzeInternal`：
  - 先 `input.trim()`；
  - raw 路径必须匹配 `PIKAFISH_FEN`，不匹配即返回
    `{ok:false, error:"无效 FEN：缺 走棋方/回合 字段（应带「 w - - 0 1」）"}`；
  - history 路径沿用原有 `PIKAFISH_POSITION` 正则（未动），错误文案独立：「无效的 Pikafish 局面历史命令」。
- ⚠️ 顺带发现：history 路径**修复前就有**完整 FEN 校验，所以只有 `analyze` 的 raw 路径会裸奔 —— 与真机复现完全吻合。

### 2. native 侧：`pikafish_jni.cpp`

- 新增 `isFenPieceChar()` 与 `looksLikeCompleteFen()`：
  - 六段形状 + **棋盘段 10 行 × 每行宽度 = 9**（数字按数字宽度累加，列分隔必须是单个 `/`）；
  - 拒绝：前导 / 尾随 / 连续空格、`fullmove=0`、走棋方非 `w|b`、第二三段非 `"-"`、行数 ≠ 10、行长 ≠ 9、
    棋盘段出现非兵种字母。
- `analyze()`：校验**前移到动会话之前**（在 `callMutex` / `sink.reset()` / MultiPV 设置**之前**）：
  - 基础形状（空串 / 超长 4096 / 含 `\n`、`\r`）→ `"error: invalid-position-input"`；
  - raw 路径直接检；history 路径先切出 `position fen` 后的内嵌 FEN 段再检；
  - 非法 → `"error: invalid-fen"`（经 Java 包装后以 `{ok:false, error:"error: invalid-fen"}` 回给脚本）。
- 这样即使 Java 层被绕过（或将来新增其他 native 调用入口），也到不了引擎。

## 验证（重建 APK 前能做的全部）

| 项 | 方法 | 结果 |
| --- | --- | --- |
| C++ 校验逻辑 | Node 逐行移植 + 14 条用例 + 2 条 history 切段 | **14/14 PASS** |
| Java 形状正则 | `javac` 真编译 9 条用例 | **8/9**（下方说明） |
| 语言服务 | 两个文件 | 0 错误 |

**14 条用例**：标准开局完整 FEN、黑方 + 大计数、空盘形状、**裸 FEN**、缺 `- - 0 1`、缺 fullmove、
`fullmove=0`、尾随空格、连续空格、走棋方 `x`、**末行宽 10**、只有 9 行、前导空格、棋盘段含非法字母 `Q`。

**Java 8/9 的第 9 条**：「末行宽 10」——你给的正则是纯形状式（不查每行宽度和），这里按设计放过；
由 native 的逐行宽度校验兜底，两层的并集覆盖全部 14 条 ⇒ 闭环。

验证脚本已留档（不塞仓库示例，放在工具产物目录）：

- `.artifacts/pikafish-fen-guard/fencheck.js` —— `node fencheck.js`；
- `.artifacts/pikafish-fen-guard/FenTest.java` —— `javac -encoding UTF-8 FenTest.java; java FenTest`；
- 说明见同目录 `README.md`。

## 兼容性（为什么不会误伤）

- 工程产 FEN 只有一条路：`src/chess/fen.js` 的 `buildFen()` 永远拼完整六段（`layout + " " + side + " - - 0 1"`）；
- 引擎入口 `src/engine/pikafish-engine.js`：有棋谱历史走 `analyzePosition`、否则 `$pikafish.analyze(fen, …)`，
  两种路径的 FEN 都是完整的 → 面板自动走棋 / 规则胜判定**行为不变**；
- 示例 `引擎与线程/QuickJS Pikafish 象棋引擎.js` 全部使用完整 FEN，且代码里加了两行警告注释。

## 重编 APK 后的复验清单

```js
var BOARD = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";
$pikafish.start({ threads: 2, hashMb: 64 });

$pikafish.analyze(BOARD, 800, 16000);                   // ① 修复前 = 进程猝死；
                                                        //    修复后应得 {ok:false, error:"无效 FEN：…"} 且进程存活
$pikafish.analyze(BOARD + " w - - 0 1", 800, 16000);    // ② 正常出着（与修复前一致）
$pikafish.analyzePosition("position fen " + BOARD + " w - - 0 1", 800, 16000);  // ③ 正常
```

④ 面板自动走棋全流程（识别 → 算棋 → 点击 → 确认）与修复前一致。

## 附带线索（未排查）

- `tombstone_14`（09-25 11:27，面板自动走棋期间）：App 崩在 **libquickjs.so `JS_DefineProperty`**
  （Script-1 线程 SIGSEGV，fault addr 0x18；09-24 有同族）——与裸 FEN 静默猝死**不是同一族**，
  且那次是有墓碑的正常崩溃，待单独排查。
