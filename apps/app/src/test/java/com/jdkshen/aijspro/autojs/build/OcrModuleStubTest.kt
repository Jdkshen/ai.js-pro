package com.jdkshen.aijspro.autojs.build

import com.stardust.autojs.runtime.api.OcrAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-129 钉子：removeOcr=true 时 __ocr__.js 必须换成**桩**（不能直删，否则守卫不可达）。
 *
 * 真机现象：直删后打包版脚本拿到的是 Module "__ocr__" not found.（模块加载器先抛），
 * 我们写在文件内部的名点文案永远不可达。
 */
class OcrModuleStubTest {
    private val stubPath = "src/main/java/com/jdkshen/aijspro/autojs/build/OcrModuleStub.kt"
    private val builderPath = "src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java"

    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun stubIsTinyAndCarriesTheSharedMessageVerbatim() {
        val content = OcrModuleStub.content(OcrAvailability.NOT_PACKAGED_MESSAGE)
        val bytes = content.toByteArray(Charsets.UTF_8)
        assertTrue("桩必须 < " + OcrModuleStub.MAX_BYTES + " 字节，实际 " + bytes.size,
            bytes.size < OcrModuleStub.MAX_BYTES)
        assertTrue("桩必须逐字包含共用文案", content.contains(OcrAvailability.NOT_PACKAGED_MESSAGE))
        assertTrue("文案必须与 engine 侧常量一致", OcrAvailability.NOT_PACKAGED_MESSAGE.contains("OCR 能力未随包"))
        assertTrue("必须含 removeOcr 字样", OcrAvailability.NOT_PACKAGED_MESSAGE.contains("optimization.removeOcr"))
    }

    @Test fun stubKeepsModuleNameAndEntryPointButThrowsOnEveryCall() {
        val content = OcrModuleStub.content(OcrAvailability.NOT_PACKAGED_MESSAGE)
        assertTrue("必须仍是模块（module.exports）", content.contains("module.exports = function (runtime, global)"))
        assertTrue("必须导出 \$ocr 入口", content.contains("global.\$ocr = ocr"))
        for (entry in listOf("create", "detect", "release", "isReleased", "liveCount")) {
            assertTrue("入口 $entry 必须在桩里（任何调用都抛）", content.contains("$entry: ocrNotPackaged"))
        }
        assertTrue("调用必须抛我们的点名错误", content.contains("throw new Error(OCR_NOT_PACKAGED)"))
    }

    @Test fun stubNeverTouchesThePrunedJavaClasses() {
        val content = OcrModuleStub.content(OcrAvailability.NOT_PACKAGED_MESSAGE)
        assertFalse("桩里不得引用 com.stardust.*（OCR 库已不在包内，引用会在加载期就炸）",
            content.contains("com.stardust"))
        assertFalse("桩里不得引用 Ocr/PPOcr", content.contains("PPOcr"))
    }

    /**
     * task-131 修正（K40 dev-1482）：removeOcr 时**不再替换真实模块**（W1 的版本内含插件分发），
     * 只有「真实模块不存在」时才回退桩。删除动作仍然禁止。
     */
    @Test fun builderKeepsTheRealModuleAndOnlyStubsWhenItIsAbsent() {
        val builder = read(builderPath)
        assertTrue("必须用 findRhinoModule 定位模块", builder.contains("OcrAssetDiscovery.findRhinoModule("))
        assertTrue("保留真实模块时要有日志说明", builder.contains("保留真实 __ocr__.js（插件优先 + 点名错误两条路都在）"))
        assertTrue("只有真实模块不存在时才写桩", builder.contains("真实 __ocr__.js 不存在 ⇒ 写了 "))
        assertTrue("桩内容仍复用 engine 唯一文案", builder.contains("OcrAvailability.NOT_PACKAGED_MESSAGE"))
        assertTrue("回退桩也要 writeBytes 到目标文件", builder.contains("writeBytes(stubTarget, stub)"))
        // 关键：不能再用桩覆盖真实模块
        val prune = builder.substringAfter("private void pruneDisabledPackagingAssets()").substringBefore("private void pruneStaleProjectResPacks")
        assertTrue("不得再出现 writeBytes(rhinoModule, stub)（那会把真实模块覆盖掉）", !prune.contains("writeBytes(rhinoModule, stub)"))
        assertTrue("日志要如实说明是 kept 还是 stubbed",
            builder.contains("kept(real module)") && builder.contains("stubbed(__ocr__.js "))
        // 直删模块的路径必须不存在（避免又回去让守卫不可达）
        assertFalse("不得再出现删除 __ocr__.js 的调用",
            builder.contains("deletePathRecursively(new File(mWorkspacePath, \"assets/modules/__ocr__.js\")"))
        // 生成器自身只允许有一份文案来源
        val stubSource = read(stubPath)
        assertFalse("桩生成器不得自己写第二份文案", stubSource.contains("OCR 能力未随包"))
    }

    /** 额外产出：把桩写到 output/task-129-2026-11-11/ocr-stub.js，供 node --check 做 JS 语法校验。 */
    @Test fun stubIsWrittenOutForJsSyntaxCheck() {
        val dir = File("../../output/task-129-2026-11-11")
        assertTrue("产出目录: " + dir.absolutePath, dir.isDirectory || dir.mkdirs())
        val file = File(dir, "ocr-stub.js")
        file.writeText(OcrModuleStub.content(OcrAvailability.NOT_PACKAGED_MESSAGE), Charsets.UTF_8)
        assertTrue(file.isFile && file.length() > 0)
    }
}
