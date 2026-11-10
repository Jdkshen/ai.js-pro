package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** task-63：打包策略（build/ 排除、原生库判据、ABI 声明）纯 JVM 测试。 */
class PackagingPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    // ---- ② build/ 默认排除，且不误伤用户自己的 build 源码目录 ----

    @Test fun packageOutputDirIsExcludedEvenWhenArtifactLivesOutsideTheProject() {
        val project = temp.newFolder("project")
        // 工程里的 build/ 只有上一次的产物（device4 实测形态）
        val build = File(project, "build").apply { mkdirs() }
        File(build, "Nycspro.apk").writeBytes(ByteArray(2048))
        File(build, "Nycspro.zip").writeBytes(ByteArray(2048))
        // 产物在工程外（脚本/APK输出/）
        val outApk = File(temp.newFolder("APK输出"), "Nycspro_v2.0.0.apk")
        val excluded = PackageOutputExclusions.projectExclusions(project, outApk, emptyList())
        assertEquals(setOf(build.canonicalPath), excluded)
        assertTrue(PackageOutputExclusions.describe(excluded, project).contains("build"))
    }

    @Test fun userSourceDirNamedBuildIsNotExcluded() {
        val project = temp.newFolder("project2")
        val build = File(project, "build").apply { mkdirs() }
        File(build, "main.js").writeText("console.log(1)")
        File(build, "project.json").writeText("{}")
        File(build, "index.html").writeText("<html/>")
        val excluded = PackageOutputExclusions.projectExclusions(project, File(temp.root, "out.apk"), emptyList())
        assertTrue("含源码的 build/ 不能被排除", excluded.isEmpty())
    }

    @Test fun markedOutputDirAndIgnoredDirsAreExcluded() {
        val project = temp.newFolder("project3")
        val marked = File(project, "build").apply { mkdirs() }
        File(marked, PackageOutputExclusions.MARKER).writeText("")
        val release = File(project, "release").apply { mkdirs() }
        File(release, "out.apk").writeBytes(ByteArray(16))
        val excluded = PackageOutputExclusions.projectExclusions(project, File(temp.root, "out.apk"),
            listOf(release.path))
        assertEquals(setOf(marked.canonicalPath, release.canonicalPath), excluded)
    }

    @Test fun outputDirInsideProjectIsStillExcluded() {
        val project = temp.newFolder("project4")
        val output = File(project, "APK输出").apply { mkdirs() }
        val excluded = PackageOutputExclusions.projectExclusions(project, File(output, "x.apk"), emptyList())
        assertEquals(setOf(output.canonicalPath), excluded)
    }

    // ---- ⑦/⑤ 产物 lib/ 每个 .so 都要对得上来源，否则是泄漏 ----

    @Test fun everyNativeLibInTheDeviceArtifactIsExplainedAndPikafishIsDropped() {
        // device4 dev-1443 产物里的 arm64 lib 清单（来自真机取证）
        val artifactLibs = listOf(
            "lib/arm64-v8a/libaijscrypto.so", "lib/arm64-v8a/libc++_shared.so",
            "lib/arm64-v8a/libquickjs.so", "lib/arm64-v8a/libquickjs_jni.so",
            "lib/arm64-v8a/libppocr.so", "lib/arm64-v8a/libmlkit_google_ocr_pipeline.so",
            "lib/arm64-v8a/libpikafish.so",
            "lib/arm64-v8a/libopencv_core.so", "lib/arm64-v8a/libopencv_dnn.so",
            "lib/arm64-v8a/libopencv_imgproc.so", "lib/arm64-v8a/libopencv_imgcodecs.so",
            "lib/arm64-v8a/libopencv_geometry.so", "lib/arm64-v8a/libopencv_java5.so",
            "lib/arm64-v8a/libtbb.so")
        val mobileGameScript = NativeLibPolicy.ProjectNeeds()   // 诺亚传说脚本：没声明象棋插件
        val leaks = NativeLibPolicy.leaks(artifactLibs, mobileGameScript)
        assertEquals("只有 pikafish 是泄漏", listOf("lib/arm64-v8a/libpikafish.so"), leaks.map { it.first })
        assertTrue(leaks.first().second.contains("象棋引擎"))

        val kept = NativeLibPolicy.filter(artifactLibs, mobileGameScript)
        assertFalse(kept.any { it.endsWith("libpikafish.so") })
        assertTrue(kept.contains("lib/arm64-v8a/libquickjs.so"))
        assertTrue(kept.contains("lib/arm64-v8a/libmlkit_google_ocr_pipeline.so"))

        // 判据（Lead 要求）：产物 lib/ 里每个 .so 要么 KEEP 且来源可解释（template/project/unknown 保守），
        // 要么 DROP 且原因是「泄漏」——不存在无法解释的条目。
        artifactLibs.forEach { entry ->
            val verdict = NativeLibPolicy.classify(entry, mobileGameScript)
            assertTrue(entry + " 的理由不能为空", verdict.reason.isNotBlank())
            when (verdict.decision) {
                NativeLibPolicy.Decision.KEEP ->
                    assertTrue(entry + " source=" + verdict.source,
                        verdict.source in setOf("template", "project", "unknown"))
                NativeLibPolicy.Decision.DROP ->
                    assertEquals(entry + " reason=" + verdict.reason, "leak", verdict.source)
            }
        }
    }

    @Test fun pikafishIsKeptOnlyWhenTheProjectReallyNeedsIt() {
        val xiangqiProject = NativeLibPolicy.ProjectNeeds(declaredPlugins = listOf("com.jdkshen.xiangqi.plugin"))
        assertEquals(NativeLibPolicy.Decision.KEEP,
            NativeLibPolicy.classify("lib/arm64-v8a/libpikafish.so", xiangqiProject).decision)
        val sourceHit = NativeLibPolicy.ProjectNeeds(usesPuzzleEngine = true)
        assertEquals(NativeLibPolicy.Decision.KEEP,
            NativeLibPolicy.classify("lib/arm64-v8a/libpikafish.so", sourceHit).decision)
    }

    @Test fun removeOpenCvDropsTheSplitOpenCvAndTbbLibs() {
        val trimmed = NativeLibPolicy.ProjectNeeds(removeOpenCv = true)
        listOf("libopencv_core.so", "libopencv_dnn.so", "libopencv_imgproc.so", "libopencv_imgcodecs.so",
            "libopencv_geometry.so", "libopencv_java5.so", "libtbb.so").forEach { name ->
            assertEquals(name, NativeLibPolicy.Decision.DROP,
                NativeLibPolicy.classify("lib/arm64-v8a/" + name, trimmed).decision)
        }
        // 模板必需的库不受 removeOpenCv 影响
        assertEquals(NativeLibPolicy.Decision.KEEP,
            NativeLibPolicy.classify("lib/arm64-v8a/libquickjs.so", trimmed).decision)
    }

    // ---- ③ ABI：不许静默少出 ----

    /** 档 1（Lead 定夺）：声明里至少一个可产出 → 产出可用的 + 显式 warning，**不挡打包**。 */
    @Test fun partialAbiGapWarnsInsteadOfFailing() {
        val resolution = AbiPolicy.resolve(listOf("arm64-v8a", "armeabi-v7a"), listOf("arm64-v8a"))
        assertEquals(listOf("arm64-v8a"), resolution.produced)
        assertEquals(listOf("armeabi-v7a"), resolution.missing)
        assertNotNull("缺一个 ABI 必须给 warning（不静默）", resolution.warning)
        val warning = resolution.warning!!
        assertTrue(warning, warning.contains("armeabi-v7a"))
        assertTrue(warning, warning.contains("32 位设备将无法安装"))
        assertTrue(warning, warning.contains("project.json"))
        assertTrue(resolution.logLine(), resolution.logLine().contains("abis declared=[arm64-v8a, armeabi-v7a]"))
        assertTrue(resolution.logLine(), resolution.logLine().contains("produced=[arm64-v8a]"))
        // 这两行就是打包时会打出来的（同一方法产出），交付样例直接取这里
        println("SAMPLE | C-PKG I | " + resolution.logLine() + " note=" + resolution.note)
        println("SAMPLE | C-PKG W | abis warning: " + warning)
    }

    /** task-101 ①：optimization.removeOcr 两态（默认 false 必须逐字不变）。 */
    @Test fun removeOcrDropsBuiltinOcrOnlyWhenDeclared() {
        val off = NativeLibPolicy.classify("lib/arm64-v8a/libppocr.so", NativeLibPolicy.ProjectNeeds())
        assertEquals(NativeLibPolicy.Decision.KEEP, off.decision)
        assertTrue("默认态的理由必须点名「内建 OCR」：" + off.reason, off.reason.contains("内建 OCR"))
        val offMlkit = NativeLibPolicy.classify("lib/arm64-v8a/libmlkit_google_ocr_pipeline.so", NativeLibPolicy.ProjectNeeds())
        assertEquals(NativeLibPolicy.Decision.KEEP, offMlkit.decision)

        val on = NativeLibPolicy.ProjectNeeds(removeOcr = true)
        val ppocr = NativeLibPolicy.classify("lib/arm64-v8a/libppocr.so", on)
        val mlkit = NativeLibPolicy.classify("lib/arm64-v8a/libmlkit_google_ocr_pipeline.so", on)
        assertEquals(NativeLibPolicy.Decision.DROP, ppocr.decision)
        assertEquals(NativeLibPolicy.Decision.DROP, mlkit.decision)
        assertTrue("丢 OCR 的理由要可抄：" + ppocr.reason, ppocr.reason.contains("removeOcr=true"))
        // 开关只影响 OCR：OpenCV/QuickJS 等不受影响
        assertEquals(NativeLibPolicy.Decision.KEEP,
            NativeLibPolicy.classify("lib/arm64-v8a/libopencv_core.so", on).decision)
        assertEquals(NativeLibPolicy.Decision.KEEP,
            NativeLibPolicy.classify("lib/arm64-v8a/libquickjs.so", on).decision)
    }

    /** task-106 复查点 3：removeOcr=true 时不能再声称 coveredBy=builtin-ocr（库已被剔）。 */
    @Test fun pluginCoverageDoesNotClaimBuiltinOcrAfterRemoveOcr() {
        val plugins = listOf("org.autojs.autojspro.plugin.mlkit.ocr")
        val normal = NativeLibPolicy.pluginCoverage(plugins)
        assertEquals("builtin-ocr", normal.single().coveredBy)
        assertTrue(normal.single().note.contains("内建"))

        val removed = NativeLibPolicy.pluginCoverage(plugins, true)
        assertNull("已被剔就不能再声称被内建覆盖：" + removed.single().note, removed.single().coveredBy)
        assertTrue(removed.single().note.contains("removeOcr"))
        // 非 OCR 插件不受影响（象棋插件两者一致）
        val puzzle = listOf("com.jdkshen.xiangqi.plugin")
        assertEquals(
            NativeLibPolicy.pluginCoverage(puzzle).single().coveredBy,
            NativeLibPolicy.pluginCoverage(puzzle, true).single().coveredBy
        )
    }

    /** task-85 ②：同一条 ABI 缺失结论只 W 一次，重复降 INFO；事实（missing）不消失。 */
    @Test fun abiWarningIsDeduplicatedPerSignatureButFactsStay() {
        val first = AbiPolicy.resolve(listOf("arm64-v8a", "armeabi-v7a"), listOf("arm64-v8a"))
        assertEquals("WARN", AbiPolicy.warningLevel(null, first))
        assertEquals("INFO", AbiPolicy.warningLevel(AbiPolicy.signature(first), first))
        assertTrue("事实不能被降噪吃掉：" + first.missing, first.missing.contains("armeabi-v7a"))
        assertNotNull(first.warning)
        // 结论变了（这里：声明的 ABI 更多）→ 重新 W
        val other = AbiPolicy.resolve(listOf("arm64-v8a", "armeabi-v7a", "x86_64"), listOf("arm64-v8a"))
        assertEquals("WARN", AbiPolicy.warningLevel(AbiPolicy.signature(first), other))
        // 没有缺失 → 不说话
        val clean = AbiPolicy.resolve(listOf("arm64-v8a"), listOf("arm64-v8a"))
        assertEquals("NONE", AbiPolicy.warningLevel(null, clean))
    }

    /** 档 2：声明的一个都产不出（真不可满足）→ 硬报错，消息保留原风格。 */
    @Test fun onlyFailsWhenNoDeclaredAbiCanBeProduced() {
        val error = runCatching {
            AbiPolicy.resolve(listOf("armeabi-v7a", "x86_64"), listOf("arm64-v8a"))
        }.exceptionOrNull()
        assertTrue("一个都产不出必须报错", error is IllegalArgumentException)
        val message = error!!.message ?: ""
        assertTrue(message, message.contains("一个都产不出"))
        assertTrue(message, message.contains("不允许静默少出"))
        assertNull("全部可产出时不该有 warning", AbiPolicy.resolve(listOf("arm64-v8a"), listOf("arm64-v8a")).warning)
    }

    /** ④ 插件：显式回显 + 覆盖不了的点名 missing（本轮不带插件 APK）。 */
    @Test fun pluginCoverageIsEchoedAndMissingOnesAreNamed() {
        val line = NativeLibPolicy.pluginSummaryLine(listOf(
            "org.autojs.autojspro.plugin.mlkit.ocr", "com.jdkshen.xiangqi.plugin", "com.example.unknown"))
        assertTrue(line, line.contains("builtin-ocr"))
        assertTrue(line, line.contains("native-lib:libpikafish.so"))
        assertTrue(line, line.contains("missing=[com.example.unknown]"))
        println("SAMPLE | C-PKG I | " + line)
        assertEquals("builtin-ocr",
            NativeLibPolicy.pluginCoverage(listOf("org.autojs.autojspro.plugin.mlkit.ocr"))[0].coveredBy)
        assertNull(NativeLibPolicy.pluginCoverage(listOf("com.example.unknown"))[0].coveredBy)
        assertTrue(NativeLibPolicy.pluginSummaryLine(emptyList()).contains("未声明插件"))
    }

    @Test fun emptyDeclarationFallsBackToAvailableAbisAndIsEchoed() {
        val resolution = AbiPolicy.resolve(emptyList(), listOf("arm64-v8a"))
        assertEquals(listOf("arm64-v8a"), resolution.produced)
        assertNull(resolution.warning)
        assertTrue(resolution.note.contains("未声明"))
    }
}
