package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** task-131 钉子：DROP 时留极小标记（只给运行期读），KEEP 一律不写。 */
class StaleResMarkerPinTest {
    private val builderPath = "src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java"

    private fun source(): String {
        val file = File(builderPath)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun markerIsWrittenOnlyInTheDropBranch() {
        val source = source()
        assertTrue("必须有标记常量", source.contains("String STALE_RES_MARKER = \"project-res.stale.json\""))
        assertTrue("必须有写入方法", source.contains("private void writeStaleResMarker("))
        assertTrue("标记内容要带 expected/actual/message",
            source.contains("marker.put(\"expected\"") && source.contains("marker.put(\"actual\"") && source.contains("marker.put(\"message\""))
        assertTrue("复用 W1 的文案变量（warning 传进去，不抄第二份）", source.contains("writeStaleResMarker(sidecar, decision, warning)"))
        // 调用点必须在 DROP 守卫之后（结构断言，不做下标算术）
        val prune = source.substringAfter("private void pruneStaleProjectResPacks()").substringBefore("private void writeStaleResMarker(")
        val guard = prune.indexOf("ProjectResSidecarPolicy.Action.DROP")
        val call = prune.indexOf("writeStaleResMarker(")
        assertTrue("DROP 守卫必须先于标记写入", guard in 0 until call)
        assertTrue("KEEP 分支不得写标记", prune.substring(0, guard).indexOf("writeStaleResMarker(") < 0)
        assertTrue("写标记必须用 writeBytes（与工作区写文件同一路径）", source.contains("writeBytes(target, bytes)"))
        assertTrue("失败不许静默", source.contains("写 project-res stale 标记失败"))
        assertEquals("不得出现第二份文案", 0, Regex("资源包与宿主资源表不匹配").findAll(source).count())
    }
}
