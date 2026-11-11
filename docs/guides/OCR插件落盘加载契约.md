# OCR 插件「落盘加载」契约（v1，冻结；W2 可直接照抄）

日期：2026-11-10。适用：**运行期按需安装的 OCR 插件**（主程序不再内建 OCR）。

> 与打包期**不是一回事**：打包期那条是 W2 的 PluginOcrSource（把插件 APK 当**源**，把 lib/模型/__ocr__.js 拷进**产物**）；本契约是**运行期**（用户下载安装插件后，宿主把插件库落到**自己的私有目录**并按绝对路径加载）。**目录不同、时机不同，不要混。**

## 1. 插件侧要暴露什么

| 项 | 约定 |
|---|---|
| 包名 | org.autojs.autojspro.plugin.ocr（新插件唯一包名） |
| 入口类+方法 | public static Object loadDefault(Context hostContext, Context pluginContext, Object runtime, Object scope)（与既有插件契约同形，见 apps/app/.../model/plugin/PluginRegistry.java:9-23 与样例 nodeplugin/Registry.java:4）。**类名/查找规则沿用宿主 PluginRegistry 现有反射常量表**，W2 建骨架时用同一份，别自造 |
| 版本 | 插件自己的 PackageInfo.versionCode（宿主不另存版本） |
| 插件 APK 内目录 | 原生库走标准 lib/<abi>/*.so；模型走 assets/ocr/models/**；脚本入口 assets/scripts/index.js |
| 库文件名 | **必须与 DT_NEEDED 的 soname 完全一致**（libppocr.so / libmlkit_google_ocr_pipeline.so / libc++_shared.so）——落盘后按绝对路径加载，名字不一致会依赖解析失败 |

## 2. 宿主侧落到哪个目录 / 命名 / 何时调

- 根目录：<host.filesDir>/plugins/<pluginPackage>/<versionCode>/（**按 versionCode 隔离**）：库 …/lib/<原文件名>.so（保持原名）；模型 …/models/**（保持相对路径）；标记 …/READY。
- **升级重拷**：versionCode 变 ⇒ 另一个目录（天然不复用旧库）；拷完删除同插件其它 versionCode 目录（清理，失败只记日志）。
- **什么时候调（顺序固定）**：⑴ 插件安装/升级后 syncPluginLibs（幂等：READY 存在且与本次摘要一致则直接返回）；⑵ 每次用 OCR 前只做**轻校验** isReady（READY + 关键库存在，**不做全量哈希**）；⑶ 轻校验失败 ⇒ 重拷一次，再失败才按 §4 报错。
- **摘要规则**：READY 里写 versionCode + abi + 各文件 sha256（按名排序）⇒ 供「同版本但文件被换」检测（只在重拷路径做）。

## 3. 怎么加载（时序 / 失败语义 / 查找路径）

- **只用绝对路径 System.load(absolutePath)，不用 System.loadLibrary(name)** —— 硬约束（已取证）：loadLibrary 只从**调用方 App 自己的 nativeLibraryDir** 解析，拿不到插件 APK 的库。
- **时序**：先按 DT_NEEDED 依赖顺序加载依赖库（如 libc++_shared.so），再加载主库（如 libppocr.so）；同名库已加载则 no-op（幂等）。
- **不注册到 System.loadLibrary 的查找路径**（Android 无公开 API）⇒ 插件库内部不得依赖隐式查找。
- **失败语义**：任何一步失败抛 PluginNativeLoader.LibLoadException（点名库名+绝对路径+原因）；**不缓存失败**（下次重试）。

## 4. 失败怎么报（点名 + 既有降级链，不新增第二份文案）

1. 库加载失败：OCR 插件库加载失败：<lib>（路径=<abs>）：<原因>。已回落到「OCR 能力未随包」；请重装/更新 OCR 插件，或关闭插件化 OCR。
2. 插件没装/没就绪：**沿用既有那条点名错误** —— OcrAvailability.NOT_PACKAGED_MESSAGE 的运行期对应句（已真机验证）。**不新增第二份文案**。
3. 两条路径都**不静默**（点名 + 可执行指引）。

## 5. 冻结接口签名（宿主运行期；W2 照这个接线，不要自造第二份）

```java
public final class PluginNativeLoader {
  public static File syncPluginLibs(Context host, String pluginPackage, int pluginVersionCode);   // 落盘+READY，幂等
  public static void load(Context host, String pluginPackage, int pluginVersionCode, String... libraryNames); // 绝对路径，依赖在前
  public static boolean isReady(Context host, String pluginPackage, int pluginVersionCode);       // 轻校验
  public static File libsDirOrNull(Context host, String pluginPackage, int pluginVersionCode);    // 未就绪 null
  public static final class LibLoadException extends IllegalStateException { }
}
```

$ocr 分发（宿主脚本层）：$plugins.load(org.autojs.autojspro.plugin.ocr) 成功 **且** PluginNativeLoader.isReady(...) ⇒ 走插件实现；否则 ⇒ §4.2 的既有降级链。

## 6. 与打包期的边界（避免两件事混）

| | 运行期插件（本契约） | 打包期（W2 PluginOcrSource） |
|---|---|---|
| 触发 | 用户下载安装插件之后 | 打包产物时 |
| 源 | 已安装的插件 APK | 插件 APK（当源） |
| 落点 | <host.filesDir>/plugins/<pkg>/<versionCode>/lib|models（私有、按版本隔离） | 产物里的 lib/** + assets/**（随包） |
| 加载 | 绝对路径 System.load（§3） | 宿主既有随包加载路径 nativeLibraryDir |
| 关系 | **同一进程不要同时走两条**：产物已内建 OCR ⇒ 直接可用 | 产物没内建 OCR ⇒ 只能走运行期插件 |

## 6.5 mlkit 为什么**不可**插件化（task-151 取证结论，2026-11-11）

- **原因（读码事实）**：`Ocr`（mlkit 后端）走 ML Kit 的 **bundled** 管线 —— `TextRecognition` + `ChineseTextRecognizerOptions`（`Ocr.java:8-10`、`:198`），初始化靠 `MlKitContext.getInstance()` / `MlKit.initialize(context)`（`:64-83`）。**整条链没有任何 modelsDir/模型目录参数** ⇒ 模型由 SDK 自己从**宿主 App 的 assets**（`assets/mlkit-google-ocr-models/**`，42 个文件，由 Gradle 从 ML Kit **AAR** 合入；排除开关见 `apps/app/build.gradle:253`、`apps/inrt/build.gradle:38` 的 `ignoreAssetsPattern`）里找 ⇒ **无法指向插件落盘目录**（运行期 assets 只读、也没有公开入口）。
- **与 ppocr 的差异**：`PPOcr` 自己读文件、且**已经有** `PPOcr(ScriptRuntime, String modelsDir, …)`（`PPOcr.java:124`）⇒ 传外部目录即可 ✓；mlkit 没有等价入口 ✗。
- **当前口径**：`optimization.removeOcr=true`（剥离后）时，`backend="mlkit"` 与 `backend="ppocr"` 都走**既有点名错误**（`OcrAvailability.NOT_PACKAGED_MESSAGE` 那套）；装了 OCR 插件时只有 **ppocr** 可用 ✓。
- **升级路径（需单独立项）**：走到「mlkit 也外置」只能换识别管线（ML Kit 自定义模型 / 换引擎）；这不是本契约的一行改动，且**可行性尚未证实**（见 task-151 的选项评估）⇒ **不要在契约里承诺 mlkit 可插件化** ✗。

## 7. 未核 / 待确认（照实）

1. 宿主 PluginRegistry 反射的**入口类字面查找规则**（类名是否固定 Registry、是否读插件 Manifest）——本契约要求 W2 与宿主用**同一份常量**，请以宿主现实现为准（我未逐行核实）。
2. Android 10+ extractNativeLibs=false 时插件 APK 的 nativeLibraryDir 形态（可能不是可直接拷贝的文件）⇒ 影响 §2 的实现方式，**不影响接口签名**，需真机确认。