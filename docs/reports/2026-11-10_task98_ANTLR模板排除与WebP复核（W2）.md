# task-98：ANTLR codegen 模板排除 + WebP 改动只读复核（W2）

## 一、改动清单
1) **ANTLR 工具模板排除（第一批落地）**
   - apps/app/build.gradle:230 packagingOptions → :236-238 的 resources.excludes 数组里加 **org/antlr/codegen/**（与既有 resources/new_api_database.ser、frameworks/** 并列；注释写明「只排 codegen，保留 org/antlr/runtime 类」）。
   - apps/inrt/build.gradle:26-33 **新增** packagingOptions { resources { excludes += ['org/antlr/codegen/**'] } }（该模块原本没有 packagingOptions；:inrt 的 release APK 就是瘦身打包模板的输入）。
   - 影响变体：:app 的 packagingOptions 与 variant 无关 ⇒ **所有 flavor（channel/engine）与 debug/release 全部生效**；:inrt 只产出 release 的 inrt-arm64-v8a（模板来源）⇒ 模板侧生效。宿主与模板两个 APK 都覆盖到了。
2) **verifySlimPackagingTemplate 加两条硬断言**（apps/app/build.gradle 任务内，紧随既有「必需条目」检查之后）
   - ① 模板里 **org/antlr/codegen/ 必须 0 条**（否则 throw，消息里直接给重建命令）；
   - ② 模板 dex 里**必须能搜到字符串 org/antlr/runtime**（复用任务里已有的 containsBytes 闭包；Node24DirectDexGenerator 依赖 runtime ⇒ 这条防「有人把 ANTLR 整包排掉」）。
3) **新增源码钉测试** apps/app/src/test/java/com/jdkshen/aijspro/autojs/build/AntlrResourceExclusionTest.kt（3 项）
   - :app 与 :inrt 的 build.gradle 都必须声明 org/antlr/codegen/**；
   - 两者都**不得**出现 org/antlr/ 整包排除或 org/antlr/runtime 排除（防过宽）。
4) **新鲜度门禁误报修复（Lead 追加，同轮并入）** tools/check-artifact-freshness.ps1
   - derived 标记的源集合改为**只看会进模板的源**：modules/*/src/main/** 与 apps/inrt/src/main/**，并显式排除 build/、**src/test/**、src/androidTest/**；
   - 注册表防漂移（Get-SourceMarkersPresent / Test-SourceContains）同样排除 test/androidTest；
   - tools/tests/artifact-freshness.test.mjs 加 2 条用例：测试源里的新方法名**不得** STALE；同一方法名出现在 src/main 里**必须** STALE（对照组，防排除过宽）。

## 二、验证方式
- **gradle DSL 有效 + 排除真的生效**（编译/资源合并级，不是出包）：
  · :inrt:compileReleaseKotlin → **exit=0**（inrt 新增的 packagingOptions 语法有效）
  · :inrt:mergeReleaseJavaResource → **exit=0**，merged_java_res/release/mergeReleaseJavaResource 下 **条目=1、其中 org/antlr/codegen=0** ⇒ 排除在资源合并阶段就已生效（不是等出包才过滤）
- **源码钉测试**：AntlrResourceExclusionTest **OK (3 tests)**（离线 kotlinc+JUnit，cwd=apps/app，读 build.gradle 与 ../inrt/build.gradle）
- **产物级硬断言（Lead 重建模板时自动验）**：verifySlimPackagingTemplate 现在会检查「codegen 0 条 + dex 含 org/antlr/runtime」。
  ⚠️ **当前会 FAIL** —— apps/app/src/main/assets/template.apk 是**签入的二进制**、还没重建（实测 :app:verifySlimPackagingTemplate FAILED，异常消息即「先重建：.\tools\build-slim-template.ps1」）。这是**预期**：重建后应 PASS（也是这次改动真正生效的判据）。
  ⚠️ 连带影响：:app:testMiuixCompatDebugUnitTest 依赖该任务 ⇒ 模板重建前跑 app 单测会先撞它；临时绕开用 **-x verifySlimPackagingTemplate**。
- **新鲜度自检**：node tools/tests/artifact-freshness.test.mjs → **RESULT 7 artifact-freshness checks passed**（含新增两条）。
- 未跑构建/装机/发布/git；未跑 :inrt:packageRelease（构建归 Lead）。

## 三、WebP 改动只读复核（未改这两个文件）
文件与体积（与你给的数字一致）：
- modules/engine/src/main/res/drawable-nodpi/ai_js_pro_logo.webp **96,278 B**（旧 PNG 1,105,606 B）
- apps/inrt/src/main/res/mipmap/ic_launcher.webp **31,130 B**（旧 PNG 1,105,606 B）
- 备份：output/logo-optimize-2026-11-10/backup/{ai_js_pro_logo.png, ic_launcher.png} 各 1,105,606 B
- 旁证：modules/engine/build/intermediates/packaged_res/{debug,release}/.../drawable-nodpi-v4/ai_js_pro_logo.webp 已存在 ⇒ 至少有一次构建已按 webp 打包

⑴ **引用点全部按资源名解析（扩展名无关）⇒ 都能解析**：
- @mipmap/ic_launcher：apps/inrt/src/main/AndroidManifest.xml:37（android:icon）、layout/activity_splash.xml:13、自定义启动图/splash.xml:6（assets 示例）
- R.mipmap.ic_launcher：InrtTaskService.kt:62、InrtExactAlarmPromptReceiver.kt:57（通知小图标）
- @drawable/ai_js_pro_logo：apps/inrt/src/main/AndroidManifest.xml:56/335/379/412（各 activity 图标）
- R.drawable.ai_js_pro_logo：MiuixAboutActivity.kt:115（painterResource）

⑵ **发现一处按文件名硬编码 .png**（**不在我的写作用域**，留给你）：
- apps/app/src/androidTest/java/com/jdkshen/aijspro/packaging/ApkBuilderEncryptionTest.java:296 与 :379：readZipEntry(outApk, "ic_launcher", ".png") —— 这是 **androidTest**（设备级），断言打包产物里有 ic_launcher.png；webp 化后产物里可能是 ic_launcher.webp ⇒ **该断言需要改**（建议：先试 .webp 再退回 .png，或按资源名前缀匹配任意扩展名）。
- 其余命中都是资源名引用或文档示例（docs/ui.html 的 img src 示例、一行历史注释），没有别的硬编码。
- 未验证点：产物里 aapt2 到底保留 .webp 还是转回 .png（要靠那条 androidTest 或解包核对；我没有出包权限）。

⑶ **minSdk = 21**（project-versions.json 的 mini；apps/app/build.gradle:168 与 apps/inrt/build.gradle:14 都引用 versions.mini）
⇒ 有损 WebP 需 **API 14+**、无损 WebP 需 **API 18+**，两者都低于 21 ⇒ **无兼容性风险**（另：targetSdk=28、compileSdk=35）

## 四、未验证边界
1. **未重建模板、未出包**（构建/装机属 Lead）⇒ 「模板里 codegen 0 条 + dex 里 runtime 在」目前只有**配置级 + 资源合并级**证据（mergeReleaseJavaResource 里 codegen=0），产物级判据交给重建时自动跑的新硬断言。
2. 模板重建前 **app 单测会被 verifySlimPackagingTemplate 依赖挡住**（预期；可 -x 绕过）。
3. ANTLR runtime 的保留只做了**静态钉**（dex 字符串 org/antlr/runtime + proguard-rules.pro:82 的 -keep）：没有真机跑 node24 打包路径（需出包 + 装机）。
4. WebP 复核是**只读**：没有出包核对该产物里 icon 的实际扩展名；androidTest 的 .png 硬编码未改（作用域外）。
5. 新鲜度门禁这次修的是 **derived 标记的源集合**（+ 注册表防漂移）；两条真阳性（AOT payload 09:35 vs project_page.dart 12:54、模板 12:59:04 vs 引擎 04:59:08Z）**保持拦**，未放宽任何口径。
