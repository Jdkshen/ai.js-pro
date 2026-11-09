# Pro 对齐 P1：`ImagePool` + 未回收护栏（2026-11-09）

## 0. 一句话

护栏**不是没有，是已经有两套**（`ResourceMonitor` 管 Mat/MatOfPoint 且**带分配栈**、`MemoryLeakDetector` 管脚本可见的 `ImageWrapper`）；
真缺的是 **`ImagePool`（作用域借用/归还）** 与 **`ColorFinder` 中间 Mat 的异常安全**。
本切片只补这两条，并把「脚本结束仍未归还」的护栏挂到**引擎既有时序点**，未改 `apps/**`、`runtime/**`。

---

## 1. 取证：现状（分配 / 回收 / 泄漏点）

### 1.1 分配路径

| 路径 | 分配物 | 位置 |
| --- | --- | --- |
| `images.captureScreen()`（投屏） | `android.media.Image` → `Bitmap` | `runtime/api/Images.java:606-622`（`ImageWrapper.ofImage`） |
| `images.captureScreen()`（无障碍） | `Bitmap` | `Images.java:597-601`；`core/image/capture/AccessibilityScreenCapturer.java` |
| `images.findColor/findMultiColors` | 中间 `Mat`/`MatOfPoint` | `core/image/ColorFinder.java` |
| 模板匹配 / 特征匹配 | 中间 `Mat` | `core/image/TemplateMatching.java`、`FeatureMatcher.java`（都已 try/finally）、`SiftMatcher.java` |
| MCP 截图 | 原始 + 裁剪/缩放位图 | `apps/app/.../mcp/McpDeviceTools.kt:213-246`（`captureFrame`，中间位图收进 `recycle` 列表） |

### 1.2 回收路径

- `core/image/ImageWrapper.recycle()`：`Bitmap.recycle()` + `OpenCVHelper.release(Mat)` + `MemoryLeakDetector.markRecycled(this)`（`ImageWrapper.java:167-177`）。
- MCP：`McpDeviceTools.kt:199 / :520` 调 `captured.recycle.forEach { it.recycle() }`。
- 无障碍截图：`AccessibilityScreenCapturer.HardwareFrame` 是 `AutoCloseable`，`toBitmap()` 里 `finally { hardware.recycle(); }`（:189-191）。
- 投屏帧：`ScreenCapturer` 自己保留 `mUnderUsingImage` 并在换代/释放时 `close()`（:376-393、:481-503）——**所有权不在 `ImageWrapper`**（`Node24CaptureConsentActivity.java:366` 有明确注释：不能提前 close，否则下一帧拿到失效 Image）。

### 1.3 已有的「未回收护栏」（两套，均已在生产接线）

1. **`modules/common/src/main/java/com/stardust/util/ResourceMonitor.java`** —— 对象级、**带分配栈**：
   - `Mat`/`MatOfPoint` 构造时 `onOpen()`（`fillInStackTrace` 记下分配现场，`Mat.java:31-74` / `MatOfPoint.java:16-34`）；
   - `release()` → `onClose()` 摘除；未 release 就被 GC → `finalize()` → `onFinalize()` → `Log.w("ResourceMonitor","UnclosedResourceDetected", 分配栈)`；
   - `AutoJs.java:86-92` 把分配异常的构造与出口都接到 `mGlobalConsole.error`。
   - 这与 Pro 的 `Debug.onAllocated(id,msg)/noMemoryLeak(id)/cleanup(id)`（`business/com/stardust/autojs/core/debug/Debug.java`）**行为对齐，且额外带分配栈**。差异：Pro 用 long id + 自己的引用清理钩子，我们用 `ResourceMonitor.Resource` + `WeakReference`。
2. **`modules/engine/src/main/java/com/stardust/autojs/runtime/api/MemoryLeakDetector.java`** —— 脚本可见 `ImageWrapper`：
   - `watch/markRecycled/drain/getLeakCount`；`$debug.setMemoryLeakDetectionEnabled`；`$debug.getMemoryLeakCount/gc` 会 drain；
   - 运行端默认开、打包端默认关（`Debug.java:44`，与 Pro `!Pref.isInrtEnvironment()` 同口径）；
   - **差异（未补）**：警告文案是 `[MemoryLeak] Image 对象没有调用 recycle() 就被回收…`，**不含分配堆栈**（补它要改 `runtime/api/MemoryLeakDetector.java`，超出本切片写作用域）。
3. 另有图像预算上限（非护栏但是同类约束）：`Node24Images.DEFAULT_MAX_LIVE_IMAGES / DEFAULT_MAX_IMAGE_PIXELS / DEFAULT_MAX_TOTAL_PIXELS`。

### 1.4 真正的缺口（本次修的两条）

- **缺 `ImagePool`**：Pro 在 `ColorFinder` 里用 `ImagePool` 登记中间 `Mat`，作用域结束统一 `release()`；我们全仓 `ImagePool`/`BitmapPool` 0 命中（`recycle` 命中都不是池）。
- **`ColorFinder.findColorInner` 不是异常安全的**：`bi`/`m`/`nonZeroPos` 都是内联 `OpenCVHelper.release`，`Core.inRange/findNonZero` 抛异常就漏（最终只能等 GC 让 `ResourceMonitor` 报出来）；`findColor/findAllPointsForColor` 里 `matOfPoint.toArray()` 抛异常同理。

---

## 2. 改动清单

| 文件 | 行 | 改动 |
| --- | --- | --- |
| `modules/engine/src/main/java/com/stardust/autojs/core/image/ImagePool.java`（新增，295 行） | 全文 | 作用域图像池：`make(resource, releaser)` / `mat()` / `mat(int,int,int)` / `mat(Mat,Rect)` / `matOfPoint(Mat)` / `matOfPoint(Point...)`（借）；`recycle(resource)`（还，幂等，不重复 release）；`maxTracked` 上限（超限报警一次 + `overflow()` 计数，**继续登记不丢弃**）；`release()`/`close()`（回收时机，强制释放 + `forced()` 计数）；`pendingCount/borrowed/returned/forced/overflow/stats()` |
| `modules/engine/src/main/java/com/stardust/autojs/core/image/ImagePools.java`（新增，176 行） | 全文 | 进程级池登记表 + **未回收护栏**：`drainAll(reason)`（强制回收未归还资源 + 警告「几个没还 + 分配点」+ 可抄提示）、`stats()`、`openPoolCount/pendingCount/forcedCount`、`setReporter`（默认 `Log.w("ImagePool", …)`）、`setDiagnosticsEnabled`（关掉只省分配点采集，不影响回收） |
| `modules/engine/src/main/java/com/stardust/autojs/core/image/ColorFinder.java` | 44-60、62-81 | `findColor` / `findAllPointsForColor`：`matOfPoint.toArray()` 改 `try { … } finally { OpenCVHelper.release(matOfPoint); }` |
| 同上 | 83-105 | `findColorInner` 改用 `try (ImagePool pool = new ImagePool(4))`：`bi`/`roi`/`nonZeroPos` 全部经池登记，正常/异常都归还；返回值 `MatOfPoint` 不进池（仍由调用方 release） |
| `modules/engine/src/main/java/com/stardust/autojs/core/image/ColorMapping.java` | 67-74 | `recycleSingleton()` 里追加 `ImagePools.drainAll("script-exit")`（这是引擎已有的「最后一个 runtime 结束」时序点：`ScriptRuntime.onExit → closeCleanables → ScriptRuntimeGlobals.close()`，与 `MemoryLeakDetector.reset()` 同一处） |
| `modules/engine/src/test/java/com/stardust/autojs/core/image/ImagePoolTest.java`（新增，213 行） | 全文 | 纯 JVM 单测 11 项：借还幂等 / 外部资源不误 release / close 强制释放且幂等 / release 后池可复用 / close 后登记报错 / 护栏带分配点与可抄提示 / 全部归还时静默 / 上限只报警一次且继续登记 / 关诊断仍回收且从 resourceId 兜底 / stats / 脚本结束时序点真的 drain |

设计取舍（有意与 Pro 不同，已写在类注释里）：
1. Pro 的 `release()` 不判重（会把同一 Mat 释放两次），这里 `release/close/recycle` 全部**幂等**；
2. Pro 的 `make(T)` 直接吃 `OpenCvMat` 共同接口；我们的 `Mat`/`MatOfPoint` 没有共同父接口（Java 名义子类型，`Mat` 不会被认成自定义接口），所以 `make` 额外收一个 `Releaser`（工厂方法已替你传好），池仍按 `ResourceMonitor.Resource` 记账以便护栏报资源 id；
3. 上限不打断作用域（**不**强制释放正在使用的 Mat），只在脚本结束/显式 drain 时强制回收。

---

## 3. 验证方式（跑过的命令 + 原始结果）

### 3.1 定向单测

```powershell
$env:JAVA_TOOL_OPTIONS='-XX:+UseSerialGC -XX:MaxMetaspaceSize=768m -XX:TieredStopAtLevel=1'
. .\tools\jdk17.ps1
.\gradlew.bat :engine:testDebugUnitTest --tests "com.stardust.autojs.core.image.ImagePoolTest" --console=plain
```

原始结果（XML，`modules/engine/build/test-results/testDebugUnitTest/TEST-com.stardust.autojs.core.image.ImagePoolTest.xml`）：

```xml
<testsuite name="com.stardust.autojs.core.image.ImagePoolTest" tests="11" skipped="0" failures="0" errors="0" ... time="0.014">
```

`BUILD SUCCESSFUL`。过程中单测**真抓到 2 个我自己写错的地方**并修掉：
1. `diagnosticsOff…` 一开始断言「关诊断后没有任何日志」——实际关诊断只去掉**分配点**，护栏照常报警（从 `resourceId=` 兜底）；断言改成「有未归还警告但含 `resourceId=`、不含 `ImagePoolTest.`」。
2. 上限用例：drain 之后池已空，只借 1 次不会超上限，改成借 2 次。

### 3.2 全量 engine 单测（回归）

```powershell
.\gradlew.bat :engine:testDebugUnitTest --console=plain   # BUILD SUCCESSFUL in 15s
```

按 XML 聚合（`suites/tests/failures/errors/skipped`）：

```
suites=122 tests=707 failures=0 errors=0 skipped=0
```

（改动前基线为 121 套件；本次 +1 套件 / +11 项。）

---

## 4. 验收口径：可观察点（K40 真机）

1. **护栏日志（logcat，tag 固定 `ImagePool`）**：
   ```
   ImagePool: [ImagePool] 未归还：脚本结束(script-exit)时仍有 N 个图像资源没有 recycle()，已强制 release；分配点：com.x.Y.z(File.java:12) <- …
   ImagePool: [ImagePool] 可抄提示：把作用域写成 try (ImagePool pool = new ImagePool()) { … }，或对每个 make()/mat()/matOfPoint() 的返回值调用 pool.recycle(x)；池统计 ImagePools.stats()。
   ImagePool: [ImagePool] 上限：未归还资源已有 N 个，超过上限 64（继续登记，不丢弃）…
   ```
   正常路径**应当看不到任何 `ImagePool` 行**（ColorFinder 的池在方法返回前就 `close()`）；它是异常路径的安全网。
2. **对象级兜底（既有，可直接复用做对照）**：`logcat | grep ResourceMonitor`（`UnclosedResourceDetected` + 分配栈）。
3. **池统计**：`ImagePools.stats()` → `{"pools":n,"pending":n,"forced":n,"borrowed":n,"returned":n,"overflow":n}`（引擎内部/单测可见；脚本侧暂不可见，见下）。
4. **功能不回归**：真机上跑 `images.findColor / findMultiColors / findAllPointsForColor`（38 或 40 之类的已知色值）确认结果与改动前一致 + 无 `ImagePool` 日志。

---

## 5. 未验证边界 / 需要真机验什么（诚实清单）

1. **`Mat`/`MatOfPoint` 的真实 native 释放只能在真机验**：JVM 单测用假资源（`Mat` 构造即走 native，`release()` 在单测里会 `UnsatisfiedLinkError`）。所以「池真的把 native Mat 释放了」属于真机证据；JVM 只证明了借/还/上限/回收时机/护栏的**语义**。
2. **`ColorFinder` 改动路径未被单测覆盖**（同样因为 native）：需要真机跑一次找色回归 + logcat 无 `ImagePool`/`ResourceMonitor` 行。
3. **护栏在生产正常路径是静默的**：`ColorFinder` 每次调用都 `close()` 自己的池，所以 `script-exit` 那条日志在正常脚本里不会出现。要**主动**看到护栏，需要真机构造一条「忘了 close 的池」路径（或临时把某处改成不 close）——单测已覆盖该文案（含分配点与提示）。
4. **脚本侧还看不到池统计**：`ImagePools` 是引擎内部类，接到脚本 console 需在 `runtime/api/Debug.java` 构造函数加一行（**超出本切片写作用域，未做**）：
   ```java
   ImagePools.setReporter(message -> { try { runtime.console.warn(message); } catch (Throwable ignored) { } });
   ```
   同理，给 `ImageWrapper` 的 `MemoryLeakDetector` 警告补「分配栈」也要改 `runtime/api/MemoryLeakDetector.java`（未做，需要 `grep -n "没有调用 recycle" ` 那一处的文案 + `watch()` 增参）。
5. **多 runtime 并发语义未真机验**：`drainAll` 只在 `ScriptRuntimeGlobals` 的 `users==0`（最后一个 runtime 结束）时触发，理论上不会误伤其它还在跑的脚本；但「池是否是跨 runtime 共享」这件事在本切片里没被构造过（`ImagePools` 是进程级登记表）。
6. **未接入的形态差异**：Pro 的 `ImageWrapper` 持有 `mediaImage` 并在 `recycle()` 里 `close()`；我们是 `ofImage()` 立刻转 `Bitmap`，`Image` 的所有权归 `ScreenCapturer`（`Node24CaptureConsentActivity.java:366` 注释 + `Images.java:219-222` 的 async 路径显式 close）。**结论：所有权清楚、不是泄漏**，但形态与 Pro 不同，未做移植。
7. **`ImagePools` 未接入 `Bitmap`/`ImageWrapper`**：按 Pro 的池只管 `Mat`/`MatOfPoint`（Bitmap 由 `MemoryLeakDetector` 管）。
