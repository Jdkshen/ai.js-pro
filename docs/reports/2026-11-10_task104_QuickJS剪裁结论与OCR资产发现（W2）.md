# task-104：QuickJS 侧结论 + OCR 资产自动发现 + 报告字段自检（W2）

默认行为不变；开关开时的条目删除与日志行两态都已钉住。

## 一、改动清单
1) ① QuickJS 侧（先取证，再决定）
   取证：在 modules/engine/src/main/assets/modules 里搜 __ocr__，**只有 qjs-plugins.js:143 的一行注释**提到它（与 Rhino 侧 __ocr__.js 的同名字段保持可观察）；**没有任何文件 require 它**。
   真正被依赖的是 **$ocr 这个全局**：qjs-plugins.js:111/130/205-226（mlkit/ppocr 会话与 create 包装）、__plugins__.js:49（插件兜底）。
   ⇒ 结论与动作：
   · **继续剪 assets/modules/__ocr__.js**（无人 require；且真机上 removeOcr 后 $ocr 走的是 W1 的点名错误，说明 $ocr 面仍在）；
   · **绝不剪 qjs-plugins.js / __plugins__.js**：它们是**共享插件门面**（非 OCR 专用），剪掉会连带干掉 QuickJS/Rhino 的插件能力 ⇒ 保留；其 OCR 分支在 $ocr 缺失时由自身 guard（qjs-plugins.js:204 检查 g.$ocr 与 g.$ocr.create 后再 return）静默降级 + 引擎点名错误兜底。
   · 日志里如实回显这一决定：ocr removed … rhinoModule=__ocr__.js quickjs=kept(qjs-plugins.js 是共享插件门面，运行期 $ocr 缺失走点名错误)。
2) ② OCR 资产自动发现（新增 apps/app/.../autojs/build/OcrAssetDiscovery.kt，纯 Kotlin/无 Android 依赖 ⇒ 可单测）
   · 判据（纯文件名、大小写无关）：目录名同时含 ocr 与 model（如 mlkit-google-ocr-models），或含 mlkit/ppocr 且含 ocr；外加 Rhino 模块文件 assets/modules/__ocr__.js（存在时）。
   · **刻意不含** qjs-plugins.js / __plugins__.js（共享插件门面）。
   · ApkBuilder 改用它；日志回显实际命中前缀：C-PKG: ocr removed (removeOcr=true) files=N bytes=M prefix=<逗号分隔的命中名>；**一个都没命中时打 W**（没找到任何 OCR 资产（模板可能改名））——不再静默 files=0。前缀同时写进报告 ocrRemovedPrefix。
3) ③ 报告字段自检：新增 PackagingReportFields.kt 作为字段名唯一真值（ocrRemovedFiles / ocrRemovedBytes / ocrRemovedPrefix / node24SkippedEntries / node24SkippedBytes），ApkBuilder **两态都写**（开关关时写 0 / 空串，不再缺省）。字段名由单测钉住。

## 二、验证方式
· 离线 kotlinc + JUnit（app/engine 已编译 class，cwd=apps/app）：PackagingPolicyTest + RemoveOcrProjectConfigTest + AntlrResourceExclusionTest + **OcrAssetDiscoveryTest** → **OK (22 tests)**。新增 4 项：
  ① 报告字段名**逐一相等且无重复**；② 发现逻辑对「模板改名」的三种形态（mlkit-google-ocr-models / mlkit-ocr / vendor-ocr-models-2026）都命中，且 **node24 / source-engine-upgrade 不得命中**；③ **共享插件门面不被碰**（发现结果只含 __ocr__.js，且源码里不出现删除 modules/qjs-plugins.js、modules/__plugins__.js，且存在「没找到任何 OCR 资产」的警告分支）；④ 空 assets 时返回空列表、describe 返回空串。
· ⚠️ :app:compileMiuixCompatDebugJavaWithJavac -x verifySlimPackagingTemplate 本轮**仍未通过**，卡在 **:apkbuilder:bundleLibCompileToJarDebug**（插件模块的 debug 打包任务；我这次改动**没有碰** :apkbuilder）——与上轮 :engine:bundleLibCompileToJarDebug 同类，属本机 debug 变体打包的既有问题；Kotlin 侧 KC exit=0 + 22 项单测全绿说明我改的代码本身编译通过。请在你那边（engine/apkbuilder debug 绿时）复跑一次。
· 未跑构建/装机/发布/git；真机由你复验（抓 C-PKG: ocr removed … / C-PKG: node24 assets skipped … 两行 + 条目数）。

## 三、未验证边界
1. **没有真机**：$ocr 在 removeOcr 后落到 W1 点名错误这一点沿用你的 K40 结论（我的改动不改变运行期路径，只改打包侧剪裁与日志）。
2. **QuickJS 的降级只有源码依据**（qjs-plugins.js:204 的 guard），没有真机跑「removeOcr 工程里调用 plugins 的 OCR 分支」。
3. 发现逻辑是**按文件名**匹配：模板若把 OCR 目录改成一个完全不含 ocr 的名字，仍会漏（但会打 W + prefix 为空 ⇒ 可见，不再静默）。
4. 报告字段的「两态稳定」由单测钉住（字段名 + 两态写入语义）；**报告 JSON 的真机取值**（打包页/桥接口读到的值）未验（属 W4 的读取面）。
5. :app 的 gradle 单测任务在本机仍被 :apkbuilder/:engine 的 debug 打包任务挡住，所以 22 项走的是离线 kotlinc+JUnit 的同一批测试类。
