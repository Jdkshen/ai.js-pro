# task-129：removeOcr 时把 __ocr__.js 换成桩（让点名错误可达）（W2）

## 一、根因（比 task-105 的守卫更靠前的一层）
task-105 把「OCR 能力未随包」的名点错误写在 assets/modules/__ocr__.js **内部**；而我在 task-104/106 的 prune 里，removeOcr=true 时把这个文件**整个删掉** ⇒ 模块加载器在加载阶段就先抛
Module "__ocr__" not found.（真机 22:01:39.460/E: Error: Module "__ocr__" not found. (<init>#120)）
⇒ 我们自己的文案**永远不可达**。结论写死：**直删会让守卫不可达**，必须保留模块名。

## 二、改动清单
1. ApkBuilder.pruneDisabledPackagingAssets()（removeOcr=true 分支）
   - **只删模型目录**：OcrAssetDiscovery.find(...) 现在只返回**目录**（它的 provided 交集判据不变）。
   - **__ocr__.js 不删**：用 OcrAssetDiscovery.findRhinoModule(...) 定位，然后写回**桩**
     OcrModuleStub.content(OcrAvailability.NOT_PACKAGED_MESSAGE)（**复用 engine 侧唯一文案，不写第二份**）。
   - 日志仍是同一行、前缀不变：C-PKG: ocr removed (removeOcr=true) files=… bytes=… prefix=… rhinoModule=stubbed(__ocr__.js 796B) quickjs=kept(...)。
   - 「没找到任何 OCR 资产」的 W 改成「目录没删到 **且** 没换桩」才打（避免换桩成功后误报）。
2. 新增 apps/app/.../autojs/build/OcrModuleStub.kt（纯 Kotlin、可单测）
   - 保留 module.exports(runtime, global) 与 $ocr 入口（create/detect/release/isReleased/liveCount **全部**指向抛错函数）；
   - 任何调用 ⇒ throw new Error(<共用文案>)；
   - **不引用任何 com.stardust.* Java 类**（OCR 库已不在包内，引用会在加载期就炸）；
   - 文案由调用方注入（OcrAvailability.NOT_PACKAGED_MESSAGE）⇒ 全仓只有一份文案。
3. OcrAssetDiscovery.kt：find() 只算目录；新增 findRhinoModule(assetsDir, provided)（都必须被模板/宿主提供，空集合 fail-safe 返回空/null）。
4. 测试
   - 新增 OcrModuleStubTest（5 项）：桩 < 2 KB 且**逐字**含共用文案（含「OCR 能力未随包」「optimization.removeOcr」）；module.exports + global.$ocr + 五个入口全抛；桩内不得出现 com.stardust / PPOcr；ApkBuilder 必须 OcrModuleStub.content + findRhinoModule + writeBytes 换桩、且**不得再有删除 __ocr__.js 的调用**、日志有 stubbed(__ocr__.js ；生成器里不得出现第二份文案；并把桩产出到 output/task-129-2026-11-11/ocr-stub.js 供 node --check。
   - OcrAssetDiscoveryTest 更新：删除清单只含模型目录；__ocr__.js 属于「换桩」目标；空清单既不删也不换桩；共享插件门面（qjs-plugins.js/__plugins__.js）不得进入任何清单。

## 三、验证方式
- **Java 侧单独编译**：modules/engine/.../runtime/api/OcrAvailability.java ⇒ **javac exit=0**。
- 离线 kotlinc + JUnit（未用 gradle）：OcrModuleStubTest + OcrAssetDiscoveryTest ⇒ **OK (10 tests)**。
- **JS 单独校验**：桩已由测试产出到 output/task-129-2026-11-11/ocr-stub.js = **796 字节（< 2 KB ✓）**，node --check ⇒ **exit=0**（语法合法）。注意：PowerShell 控制台回显是 GBK 乱码，**文件本身是 UTF-8**（测试按 UTF-8 写出，并断言文案逐字包含）。
- ApkBuilder.java 单文件 javac：仍是那套离线 classpath 限制（拼不齐 engine/rhino 模块产物）⇒ **未通过、未记为已验证**；但本次 Java 侧改动的三个调用点（OcrAssetDiscovery.findRhinoModule / OcrModuleStub.content / writeBytes）都在同一模块内、且前两个由 Kotlin 编译 + 测试覆盖。
- 未跑 gradle / 装机 / 发布 / git。

## 四、未验证边界
1. 真机验收归 Lead：K40 重打 slim-ocr ⇒ 打包版 LogActivity 出现点名错误（含「OCR 能力未随包」/optimization.removeOcr），**不再**是 Module "__ocr__" not found.；产物 OCR 条目仍 0（模型与原生库照旧剪掉）；桩 < 2 KB。
2. QuickJS 侧（qjs-plugins.js）仍在包内，且 $ocr 现在**存在**（桩）⇒ 它的 guard 会走到我们抛出的点名错误（顺带改善），但**本轮没有真机验证** QuickJS 路径。
3. 桩的文案来自 engine 的 OcrAvailability（W1 域，我只读复用）；若他把文案改了，桩自动跟随（同源），无需改我这里。
4. Node24 的 ocr.js 路径未动（本轮只修「模块被删导致守卫不可达」这个缺口）。
