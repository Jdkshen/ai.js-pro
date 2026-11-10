# task-101：removeOcr 与 nodejs=disabled 两条打包开关（W2）

默认行为零变化：两个开关都不成立时，产物条目集合与 dev-1463 逐条一致（代码级论证 + Lead 真机对照命令见 §4）。

## 一、改动清单

### ① optimization.removeOcr（默认 false，与 removeOpenCv 同形）
- modules/engine/src/main/java/com/stardust/autojs/project/ProjectConfig.java
  · 新增 transient 字段 mRemoveOcr + 公开只读 isRemoveOcr()（放在 toJson() 之前）；
  · ProDialect.deserialize 里读 optimization.removeOcr（只在该键存在时赋值 ⇒ 未设置保持 null/false）；
  · transient ⇒ **写路径逐字不变**（引擎本来就不序列化 optimization，见新增单测）。
- apps/app/src/main/java/com/jdkshen/aijspro/autojs/build/NativeLibPolicy.kt
  · ProjectNeeds 追加第 4 个参数 removeOcr（**加在末尾** ⇒ 既有按位置构造的调用点不受影响）；
  · OCR 分支：name in OCR_LIBS（libppocr.so / libmlkit_google_ocr_pipeline.so）时，removeOcr=true → DROP（理由含 removeOcr=true，可抄）；false/未设置 → KEEP「内建 OCR（ppocr/mlkit pipeline）覆盖 Pro 的 mlkit 插件能力」**逐字不变**。
- apps/app/src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java
  · 读 optimization.removeOcr（紧挨 removeOpenCv 的读法）；
  · new NativeLibPolicy.ProjectNeeds(removeOpenCv, plugins, usesPuzzleEngine, removeOcr)；
  · 新增 pruneDisabledPackagingAssets()（在 stageSjbcAssets() 之后、enforcePackagingPolicy() 之前调用）：removeOcr=true 时递归删 assets/mlkit-google-ocr-models/** 与 assets/modules/__ocr__.js，日志一行 **C-PKG: ocr removed (removeOcr=true) files=N bytes=M**，并把 ocrRemovedFiles/ocrRemovedBytes 写进打包报告 + 一条 packaging warning；
  · dropped native libs 那行日志补 removeOcr=<bool>（仅文案，不影响条目）。

### ② features.nodejs=disabled ⇒ 运行资产不随包
- pruneDisabledPackagingAssets()：nodejs=disabled 时递归删 assets/node24/** 与 assets/source-engine-upgrade/**（含与 lib/ 重复的 libc++_shared.so），日志一行 **C-PKG: node24 assets skipped (nodejs=disabled) entries=N bytes=M**，报告写 node24SkippedEntries/node24SkippedBytes。
- **既有校验改为「跳过并说明」**：node24 那段的入口判据变成
  if (isNodeJsDisabled(project)) { Log.i(C-PKG, "node24 runtime copy skipped (nodejs=disabled)：assets/node24/** 与 assets/source-engine-upgrade/** 不随包；node24 相关校验一并跳过") }
  else if (Node24PackagingAssets.requiresRuntime(nodeEntry, project)) { ...copyFromHost + Node24TemplateConsistency.verifyWorkspace... }
  ⇒ 该路径上 Node24PackagingAssets.copyFromHost / Node24TemplateConsistency.verifyWorkspace **根本不会被调用**，不会因资源缺失失败；其余工程（node 工程 / 开了 nodejs 特性位）走原分支逐字不变。
- 判定单一来源：isNodeJsDisabled(project) = features.nodejs 字符串等于（忽略大小写）disabled；与 requiresRuntime 的 enabled/auto/true 判定互补，不写第二份真值。

## 二、单测（离线 kotlinc+JUnit，18/18 OK）
1) PackagingPolicyTest.removeOcrDropsBuiltinOcrOnlyWhenDeclared（两态）：默认 libppocr.so / libmlkit_google_ocr_pipeline.so 都 KEEP 且理由含「内建 OCR」；removeOcr=true 都 DROP 且理由含 removeOcr=true；**开关只看 OCR**（libopencv_core.so / libquickjs.so 两态都 KEEP）。
2) RemoveOcrProjectConfigTest.removeOcrIsReadButNeverWritten：未声明 false；声明 true 读得到；toJson() 三次调用逐字相同且都不含 removeOcr。
3) RemoveOcrProjectConfigTest.optimizationBlockIsNotPartOfTheWritePath：钉住「引擎 toJson 本来就不输出 optimization/removeOpenCv」（防以后有人加字段改坏写路径）。
4) AntlrResourceExclusionTest（task-98 钉子）继续通过。

## 三、编译验证
- **:engine:compileReleaseJavaWithJavac → exit=0**（ProjectConfig 改动编译通过）。
- **:app:compileMiuixCompatDebugJavaWithJavac 未通过，但卡在 :engine:bundleLibCompileToJarDebug（debug 变体打包任务）—— 不是本次改动**：release 变体 exit=0，且本会话此前多次看到同一 debug 任务挂（W1 引擎在制）。我用的就是你给的命令（带 -x verifySlimPackagingTemplate）。engine debug 绿后请复跑。
- 单测走离线 kotlinc+JUnit（app/engine 的已编译 class）：TESTS **OK (18 tests)**。

## 四、默认态「逐条一致」的论证与对照命令
代码级：pruneDisabledPackagingAssets() 的两个分支都在 if (removeOcr) / if (nodejsDisabled) 之内；node24 copy/verify 仅在禁用时被 else 短路。默认态**没有任何删除动作**，唯一变化是 dropped native libs 日志多了 removeOcr=false（文本，不影响条目）。
Lead 真机对照（dev-1463 同工程同路径）：
~~~
unzip -Z1 before.apk | sort > before.txt
unzip -Z1 after.apk  | sort > after.txt
diff before.txt after.txt          # 期望：无输出
unzip -l after.apk | grep -cE "mlkit-google-ocr-models|__ocr__|assets/node24/|source-engine-upgrade"   # 默认：不应为 0
~~~
开关打开时：removeOcr=true → lib/ 里 libppocr.so 与 libmlkit_google_ocr_pipeline.so 为 0、assets/mlkit-google-ocr-models 为 0、assets/modules/__ocr__.js 不存在，其余 lib 不变；nodejs=disabled → assets/node24/** 与 assets/source-engine-upgrade/** 为 0；两例都应出现对应的 C-PKG 行。

## 五、未验证边界
1. **未出包、未真机**（构建/装机归 Lead）：条目为 0 / 其余不变 / 打包版能起 / 诊断 node24=absent / $ocr 点名报错 全部待 K40 复验。
2. assets/modules/__ocr__.js 是 **Rhino** 侧 OCR 模块；QuickJS 侧（qjs-plugins.js 一类）**没有被 removeOcr 剪裁**，运行期由 W1 的点名错误兜住；要不要连 QuickJS 侧一起剪需 W1 确认依赖后再定（本轮不动，避免剪掉被 require 的文件）。
3. assets/mlkit-google-ocr-models/** 路径按 K40 清单（42 项 / 2.49M）写死；模板改名会让 files/bytes 变 0（没做自动发现）。
4. ProjectConfig.isRemoveOcr() 只是只读口径；**UI 开关归 W4**（本轮未动 Dart）。
5. 本机没跑 :app 完整单测任务（被 engine debug 挡住）；18 项是离线跑的同一批测试类。
